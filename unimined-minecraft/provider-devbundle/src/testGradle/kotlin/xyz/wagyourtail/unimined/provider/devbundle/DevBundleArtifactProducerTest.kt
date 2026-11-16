package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import java.nio.file.Files
import java.nio.file.Path

/** `devbundle` artifact producer — `produce()` inside the
 *  raw-artifact transform: materializes the declared bundle, reads
 *  its config, stages the MC server jar from the (pre-cached)
 *  manifest chain, then runs the bundle's paperclip via
 *  `ProcessBuilder`. The round-trip bundles below come from
 *  [DevBundleExport] itself — the generated paperclip's bootstrap
 *  class REALLY runs in a subprocess. No network. */
class DevBundleArtifactProducerTest {

    @TempDir
    lateinit var dir: Path

    private fun spec(
        source: DeclaredSource?, format: String? = null
    ) = RuntimeProductSpec(
        environmentId = "main",
        sourceSetName = "main",
        version = RuntimeVersionSpec("1.20.4"),
        side = Side.MERGED,
        loader = null,
        declaredSource = source?.let {
            DeclaredSourceSpec(it, "official", format) }
    )

    private fun request(
        cacheDir: Path, key: ArtifactKey
    ) = ArtifactProductionRequest(
        cacheDir = cacheDir,
        artifactKey = key,
        offline = true,
        logger = uniminedLogger(
            Logging.getLogger(DevBundleArtifactProducer::class.java))
    )

    /** A self-contained dev bundle reproducing [serverBytes] —
     *  exported through the real [DevBundleExport] writer. */
    private fun exportBundle(serverBytes: ByteArray): Path {
        val serverJar = dir.resolve("srv.jar")
        Files.write(serverJar, serverBytes)
        val out = dir.resolve("fork-bundle.zip")
        DevBundleExport.export(
            DevBundleExport.Spec(
                minecraftVersion = "1.20.4",
                bundleId = "test-fork-1.20.4",
                serverJar = serverJar,
                libraries = emptyList(),
                libraryRepositories = emptyList()
            ),
            out
        )
        return out
    }

    /** Pre-stage the manifest → version-json → server.jar chain the
     *  producer resolves for `1.20.4` — every fetch hits the request
     *  cache, nothing reaches the network. */
    private fun stageMojang(cache: Path, vanillaBytes: ByteArray) {
        fun stage(rel: String, bytes: ByteArray) {
            val f = cache.resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        stage("metadata/version-manifest-v2.json", """
            {"latest":{"release":"1.20.4","snapshot":"1.20.4"},
             "versions":[
              {"id":"1.20.4","type":"release",
               "url":"https://example/1.20.4.json",
               "time":"t","releaseTime":"t"}
            ]}
        """.trimIndent().toByteArray())
        stage("metadata/versions/1.20.4.json", """
            {
              "id":"1.20.4","type":"release",
              "downloads":{
                "server":{"url":"https://example/server.jar"}
              },
              "javaVersion":{"component":"java-runtime","majorVersion":17}
            }
        """.trimIndent().toByteArray())
        stage("versions/1.20.4/server.jar", vanillaBytes)
    }

    // ---------- selection ----------

    @Test
    fun `claims only format = devbundle declared sources`() {
        val producer = DevBundleArtifactProducer()
        val bundle = DeclaredSource.LocalFile("bundle.zip")
        assertTrue(producer.supports(spec(bundle, "devbundle")))
        // an untagged declared source is legacydist's floor claim,
        // and a bare spec is native's
        assertTrue(!producer.supports(spec(bundle)))
        assertTrue(!producer.supports(spec(null)))
        assertTrue(!producer.supports(spec(bundle, "obf")))

        // priority 5 sits between conversion's table (10) and
        // legacydist's floor (4) — the marked spec negotiates here
        val registry = ProductArtifactProducerRegistry()
        assertTrue(registry.select(spec(bundle, "devbundle"))
                is DevBundleArtifactProducer)
        assertTrue(registry.select(spec(bundle, "devbundle"),
            "devbundle") is DevBundleArtifactProducer)
    }

    // ---------- rejections ----------

    @Test
    fun `client key fails — dev bundles are server-only`() {
        val e = assertThrows(IllegalStateException::class.java) {
            DevBundleArtifactProducer().produce(
                spec(DeclaredSource.LocalFile("b.zip"), "devbundle"),
                request(dir.resolve("cache"), ArtifactKey.CLIENT))
        }
        assertTrue("server-only" in e.message!!)
    }

    @Test
    fun `spec without a declaredSource fails with a clear error`() {
        // supports() wouldn't claim this, but produce() still
        // guards direct invocation
        val e = assertThrows(IllegalStateException::class.java) {
            DevBundleArtifactProducer().produce(
                spec(null), request(dir.resolve("cache"),
                    ArtifactKey.MERGED))
        }
        assertTrue("requires a declaredSource" in e.message!!)
    }

    @Test
    fun `producer source cannot run inside the transform`() {
        val e = assertThrows(IllegalStateException::class.java) {
            DevBundleArtifactProducer().produce(
                spec(
                    DeclaredSource.Producer(
                        "build", output = "bundle.zip"),
                    "devbundle"),
                request(dir.resolve("cache"), ArtifactKey.MERGED))
        }
        assertTrue("cannot run inside the artifact transform"
                in e.message!!)
    }

    @Test
    fun `maven source is a provider-side materialization error`() {
        val e = assertThrows(IllegalStateException::class.java) {
            DevBundleArtifactProducer().produce(
                spec(DeclaredSource.Maven("g:a:1.0"), "devbundle"),
                request(dir.resolve("cache"), ArtifactKey.MERGED))
        }
        assertTrue("unexpected here" in e.message!!)
    }

    // ---------- production ----------

    @Test
    fun `produce runs the exported bundle's paperclip end to end`() {
        val cache = dir.resolve("cache")
        val embedded = byteArrayOf(5, 4, 3, 2, 1)
        val bundle = exportBundle(embedded)
        stageMojang(cache, byteArrayOf(0))

        val out = DevBundleArtifactProducer().produce(
            spec(DeclaredSource.LocalFile(bundle.toString()),
                "devbundle"),
            request(cache, ArtifactKey.MERGED))
        // content-keyed bundle dir → patched-server.jar carries the
        // embedded prod jar the paperclip stand-in extracted
        assertEquals("patched-server.jar", out.fileName.toString())
        assertEquals(embedded.toList(), Files.readAllBytes(out).toList())
        assertTrue(cache.resolve("devbundle")
            .let { out.startsWith(it) })
    }

    @Test
    fun `produce answers the server key too`() {
        val cache = dir.resolve("cache")
        val embedded = byteArrayOf(9, 8, 7)
        val bundle = exportBundle(embedded)
        stageMojang(cache, byteArrayOf(0))
        val out = DevBundleArtifactProducer().produce(
            spec(DeclaredSource.LocalFile(bundle.toString()),
                "devbundle"),
            request(cache, ArtifactKey.SERVER))
        assertEquals(embedded.toList(), Files.readAllBytes(out).toList())
    }

    @Test
    fun `url source serves the staged bundle`() {
        val cache = dir.resolve("cache")
        val embedded = byteArrayOf(1, 1, 2, 3)
        val bundle = exportBundle(embedded)
        stageMojang(cache, byteArrayOf(0))
        // the declared URL's fetch lands on the content-keyed
        // bundle dir — pre-place it so the download never fires
        val source = DeclaredSource.Url("https://example/fork.zip")
        val staged = DevBundleProduction.bundleDir(source, cache)
            .resolve("bundle.zip")
        Files.createDirectories(staged.parent)
        Files.copy(bundle, staged)
        val out = DevBundleArtifactProducer().produce(
            spec(source, "devbundle"),
            request(cache, ArtifactKey.MERGED))
        assertEquals(embedded.toList(), Files.readAllBytes(out).toList())
    }

}
