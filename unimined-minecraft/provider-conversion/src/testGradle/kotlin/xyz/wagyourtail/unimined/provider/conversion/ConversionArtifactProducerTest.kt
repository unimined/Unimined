package xyz.wagyourtail.unimined.provider.conversion

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** `conversion` artifact producer — the transform-side `produce()`
 *  entrypoint [ConversionProvider]'s materialize() wraps. Dist
 *  downloads and per-version manifests are pre-staged in the
 *  request's cache dir; no fetch ever reaches the network. */
class ConversionArtifactProducerTest {

    @TempDir
    lateinit var dir: Path

    // `.dat` entries — JarMerger member-merges `.class` entries
    // through ASM, which would reject the text fixture bytes
    private val clientJar = jar(mapOf(
        "game/Client.dat" to "client", "lib/Shared.dat" to "client"))
    private val serverJar = jar(mapOf(
        "game/Server.dat" to "server", "lib/Shared.dat" to "server"))
    private val libJar = jar(mapOf("lib/Shared.dat" to "lib"))

    private fun jar(entries: Map<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha(bytes: ByteArray, algorithm: String = "SHA-1") =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    private fun stage(cache: Path, rel: String, bytes: ByteArray) {
        val f = cache.resolve(rel)
        Files.createDirectories(f.parent)
        Files.write(f, bytes)
    }

    private fun spec(
        product: String,
        version: String = "1.0",
        source: DeclaredSource? = null
    ) = RuntimeProductSpec(
        environmentId = "main",
        sourceSetName = "main",
        version = RuntimeVersionSpec(version),
        side = Side.CLIENT,
        loader = null,
        product = product,
        declaredSource = source?.let {
            DeclaredSourceSpec(it, "official", null) }
    )

    private fun request(
        cacheDir: Path,
        key: ArtifactKey,
        libraryJars: List<Path> = emptyList(),
        offline: Boolean = true
    ) = ArtifactProductionRequest(
        cacheDir = cacheDir,
        artifactKey = key,
        offline = offline,
        libraryJars = libraryJars,
        logger = uniminedLogger(
            Logging.getLogger(ConversionArtifactProducer::class.java))
    )

    // ---------- selection ----------

    @Test
    fun `claims product-table products, loses others to native`() {
        val producer = ConversionArtifactProducer()
        assertTrue(producer.supports(spec("minecraft:reindev")))
        assertTrue(producer.supports(spec("minecraft:bta")))
        assertTrue(producer.supports(spec("minecraft:nsss")))
        // declaredSource alone doesn't claim — a `custom:` product
        // stays on the native catch-all
        assertTrue(!producer.supports(spec("custom:weird",
            source = DeclaredSource.LocalFile("x.jar"))))

        val registry = ProductArtifactProducerRegistry()
        // priority 10 beats native's catch-all (0) on its own products
        assertTrue(registry.select(spec("minecraft:bta"))
                is ConversionArtifactProducer)
        assertTrue(registry.select(spec("minecraft:nsss"),
            "conversion") is ConversionArtifactProducer)
        // `custom:` products + declared sources stay on the floor claim
        assertTrue(registry.select(spec("custom:weird",
            source = DeclaredSource.LocalFile("x.jar")))
                is xyz.wagyourtail.unimined.provider.native
                    .NativeArtifactProducer)
    }

    // ---------- declared-source path ----------

    @Test
    fun `declared local file is the artifact for every key`() {
        val dist = dir.resolve("dist.jar")
            .apply { Files.write(this, byteArrayOf(1, 2, 3)) }
        val s = spec("minecraft:nsss",
            source = DeclaredSource.LocalFile(dist.toString()))
        // a single dist jar can't be side-split or merged — the file
        // itself answers every key
        for (key in listOf(ArtifactKey.CLIENT, ArtifactKey.SERVER,
            ArtifactKey.MERGED, ArtifactKey.COMMON)) {
            assertEquals(dist, ConversionArtifactProducer()
                .produce(s, request(dir.resolve("cache"), key)))
        }
    }

    @Test
    fun `missing declared file fails with a clear error`() {
        val e = assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                spec("minecraft:nsss",
                    source = DeclaredSource.LocalFile(
                        dir.resolve("nope.jar").toString())),
                request(dir.resolve("cache"), ArtifactKey.CLIENT))
        }
        assertTrue("does not exist" in e.message!!)
    }

    @Test
    fun `declared url fetches the cached jar and verifies sha256`() {
        val cache = dir.resolve("cache")
        stage(cache, "nsss/1.0/declared.jar", byteArrayOf(4, 5, 6))
        val pinned = sha(byteArrayOf(4, 5, 6), "SHA-256")
        val out = ConversionArtifactProducer().produce(
            spec("minecraft:nsss",
                source = DeclaredSource.Url(
                    "https://example/dist.jar", pinned)),
            request(cache, ArtifactKey.CLIENT))
        assertEquals(cache.resolve("nsss/1.0/declared.jar"), out)
        assertEquals(byteArrayOf(4, 5, 6).toList(),
            Files.readAllBytes(out).toList())
    }

    @Test
    fun `declared url sha256 mismatch fails integrity`() {
        val cache = dir.resolve("cache")
        stage(cache, "nsss/1.0/declared.jar", byteArrayOf(4, 5, 6))
        // fetch serves the cached bytes unpinned — the sha256 check
        // is verifySha256's job and it reports the mismatch
        val e = assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                spec("minecraft:nsss",
                    source = DeclaredSource.Url(
                        "https://example/dist.jar", "00".repeat(32))),
                request(cache, ArtifactKey.CLIENT))
        }
        assertTrue("sha256 mismatch" in e.message!!)
    }

    @Test
    fun `verifySha256 reports the digest mismatch`() {
        val f = dir.resolve("dist.jar")
            .apply { Files.write(this, byteArrayOf(1)) }
        val e = assertThrows(IllegalStateException::class.java) {
            verifySha256(f, "ab".repeat(32),
                Logging.getLogger(ConversionArtifactProducer::class.java))
        }
        assertTrue("sha256 mismatch" in e.message!!)
        // null pin only warns — the file is accepted
        verifySha256(f, null,
            Logging.getLogger(ConversionArtifactProducer::class.java))
    }

    @Test
    fun `declared maven and producer sources fail loudly`() {
        assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                spec("minecraft:nsss",
                    source = DeclaredSource.Maven("g:a:1.0")),
                request(dir.resolve("cache"), ArtifactKey.CLIENT))
        }
        assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                spec("minecraft:nsss",
                    source = DeclaredSource.Producer(
                        "java", output = "out.jar")),
                request(dir.resolve("cache"), ArtifactKey.CLIENT))
        }
    }

    // ---------- product-table path ----------

    /** `bta` — the manifest-pinned product: its per-version manifest
     *  IS a Mojang version doc; sided downloads carry sha1s. */
    private fun stageBta(cache: Path, version: String = "7.3_04") {
        stage(cache, "metadata/conversion/bta-$version.json", """
            {
              "id":"bta-$version","type":"release","mainClass":"bta.Main",
              "downloads":{
                "client":{"url":"https://example/bta-client.jar","sha1":"${sha(clientJar)}","size":${clientJar.size}},
                "server":{"url":"https://example/bta-server.jar","sha1":"${sha(serverJar)}","size":${serverJar.size}}
              }
            }
        """.trimIndent().toByteArray())
        stage(cache, "bta/$version/client.jar", clientJar)
        stage(cache, "bta/$version/server.jar", serverJar)
    }

    @Test
    fun `bta client and server come from the manifest downloads`() {
        val cache = dir.resolve("cache")
        stageBta(cache)
        val s = spec("minecraft:bta", version = "7.3_04")
        val client = ConversionArtifactProducer().produce(
            s, request(cache, ArtifactKey.CLIENT))
        assertEquals(clientJar.toList(),
            Files.readAllBytes(client).toList())
        val server = ConversionArtifactProducer().produce(
            s, request(cache, ArtifactKey.SERVER))
        assertEquals(serverJar.toList(),
            Files.readAllBytes(server).toList())
    }

    @Test
    fun `bta merges and intersects — the mergeable product`() {
        val cache = dir.resolve("cache")
        stageBta(cache)
        val s = spec("minecraft:bta", version = "7.3_04")
        val merged = ConversionArtifactProducer().produce(
            s, request(cache, ArtifactKey.MERGED))
        ZipFile(merged.toFile()).use { zip ->
            assertTrue(zip.getEntry("game/Client.dat") != null)
            assertTrue(zip.getEntry("game/Server.dat") != null)
        }
        val common = ConversionArtifactProducer().produce(
            s, request(cache, ArtifactKey.COMMON))
        ZipFile(common.toFile()).use { zip ->
            assertEquals(setOf("lib/Shared.dat"),
                zip.entries().asSequence().map { it.name }
                    .filter { !it.endsWith("/") }.toSet())
        }
    }

    @Test
    fun `merge strips bundled-library copies from the inputs`() {
        val cache = dir.resolve("cache")
        stageBta(cache)
        val lib = dir.resolve("slf4j-api.jar")
        Files.write(lib, libJar)
        // `extraLibraries` declared (bta) + resolved libraryJars → the
        // shaded `lib/Shared.class` copy strips out of both merge
        // inputs before JarMerger runs
        val merged = ConversionArtifactProducer().produce(
            spec("minecraft:bta", version = "7.3_04"),
            request(cache, ArtifactKey.MERGED,
                libraryJars = listOf(lib)))
        ZipFile(merged.toFile()).use { zip ->
            assertTrue(zip.getEntry("lib/Shared.dat") == null)
            assertTrue(zip.getEntry("game/Client.dat") != null)
        }
    }

    @Test
    fun `non-mergeable product rejects merged and common`() {
        // reindev's sided dists diverge — nothing is fetched, the
        // check fails before fetchSide ever runs
        val s = spec("minecraft:reindev", version = "2.9")
        assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                s, request(dir.resolve("cache"), ArtifactKey.MERGED))
        }
        assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                s, request(dir.resolve("cache"), ArtifactKey.COMMON))
        }
    }

    @Test
    fun `reindev client resolves the url-template cache`() {
        val cache = dir.resolve("cache")
        stage(cache, "reindev/2.9/client.jar", clientJar)
        // no manifest channel — clientUrls/serverUrls templates,
        // first cached hit pins the artifact
        val out = ConversionArtifactProducer().produce(
            spec("minecraft:reindev", version = "2.9"),
            request(cache, ArtifactKey.CLIENT))
        assertEquals(clientJar.toList(),
            Files.readAllBytes(out).toList())
    }

    @Test
    fun `missing manifest download entry fails with a clear error`() {
        val cache = dir.resolve("cache")
        stage(cache, "metadata/conversion/bta-7.3_04.json", """
            {"id":"bta-7.3_04","type":"release",
             "downloads":{"client":{"url":"https://x/c.jar"}}}
        """.trimIndent().toByteArray())
        assertThrows(IllegalStateException::class.java) {
            ConversionArtifactProducer().produce(
                spec("minecraft:bta", version = "7.3_04"),
                request(cache, ArtifactKey.SERVER))
        }
    }

    // ---------- internal helpers ----------

    @Test
    fun `fetchFirstOf walks candidates until one resolves`() {
        val downloader = Downloader(
            cacheDir = dir.resolve("cache"),
            retryBackoffMs = { 0 },
            fetch = { url ->
                if (url.endsWith("good.jar")) byteArrayOf(1)
                else throw IOException("nope: $url")
            })
        val out = fetchFirstOf(
            downloader,
            listOf("https://x/a.jar", "https://x/good.jar"),
            "p/1.0/client.jar")
        assertEquals(byteArrayOf(1).toList(),
            Files.readAllBytes(out).toList())
        // the first hit pins the shared cache key
        assertEquals(dir.resolve("cache/p/1.0/client.jar"), out)
    }

    @Test
    fun `fetchFirstOf names every tried url when all miss`() {
        val downloader = Downloader(
            cacheDir = dir.resolve("cache"),
            retryBackoffMs = { 0 },
            fetch = { url -> throw IOException("nope: $url") })
        val e = assertThrows(IllegalStateException::class.java) {
            fetchFirstOf(
                downloader,
                listOf("https://x/a.jar", "https://x/b.jar"),
                "p/1.0/client.jar")
        }
        assertTrue("https://x/a.jar" in e.message!!)
        assertTrue("https://x/b.jar" in e.message!!)
    }

}
