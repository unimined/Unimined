package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.ProductVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** `native` artifact producer — the transform-side `produce()`
 *  entrypoint [NativeProvider]'s lazy artifact providers wrap. Every
 *  download/metadata channel is pre-staged in the request's cache dir
 *  so no fetch ever fires (see `Downloader.fetch`: a valid cached
 *  file is reused indefinitely). */
class NativeArtifactProducerTest {

    @TempDir
    lateinit var dir: Path

    private val clientJar = jar(mapOf(
        "client.txt" to "client", "shared.txt" to "client"))
    private val serverJar = jar(mapOf(
        "server.txt" to "server", "shared.txt" to "server"))

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

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1")
        .digest(bytes).toHex()

    /** Pre-stage the manifest → version-json → sided jars chain
     *  `produce` resolves: `metadata/version-manifest-v2.json`
     *  (cache name for [ManifestResolver.VERSION_MANIFEST_V2]),
     *  `metadata/versions/<id>.json`, and
     *  `versions/<id>/{client,server}.jar`. */
    private fun stageCache(cache: Path, versionId: String = "1.20.1") {
        fun stage(rel: String, bytes: ByteArray) {
            val f = cache.resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        stage("metadata/version-manifest-v2.json", """
            {"latest":{"release":"$versionId","snapshot":"$versionId"},"versions":[
              {"id":"$versionId","type":"release","url":"https://example/version.json","time":"t","releaseTime":"t"}
            ]}
        """.trimIndent().toByteArray())
        stage("metadata/versions/$versionId.json", """
            {
              "id":"$versionId","type":"release","mainClass":"example.Main",
              "downloads":{
                "client":{"url":"https://example/client.jar","sha1":"${sha1(clientJar)}","size":${clientJar.size}},
                "server":{"url":"https://example/server.jar","sha1":"${sha1(serverJar)}","size":${serverJar.size}}
              },
              "libraries":[{"name":"org.example:library:1.0"}]
            }
        """.trimIndent().toByteArray())
        stage("versions/$versionId/client.jar", clientJar)
        stage("versions/$versionId/server.jar", serverJar)
    }

    private fun spec(
        side: Side, loader: LoaderSpec? = null, mcVersion: String = "1.20.1"
    ) = object : ProductSpec {
        override val version: ProductVersionSpec = RuntimeVersionSpec(mcVersion)
        override val side = side
        override val loader = loader
    }

    private fun request(
        cacheDir: Path, key: ArtifactKey, offline: Boolean = false,
        preMerge: ((client: Path, server: Path) -> Pair<Path, Path>)? = null
    ) = ArtifactProductionRequest(
        cacheDir = cacheDir,
        artifactKey = key,
        offline = offline,
        preMerge = preMerge,
        logger = uniminedLogger(
            Logging.getLogger(NativeArtifactProducer::class.java))
    )

    @Test
    fun `registry selects the catch-all native producer`() {
        val registry = ProductArtifactProducerRegistry()
        // the only producer on this module's classpath — claims any spec
        val picked = registry.select(spec(Side.CLIENT))
        assertTrue(picked is NativeArtifactProducer)
        assertEquals(3, picked.artifactFormatVersion)
    }

    @Test
    fun `produce CLIENT returns the cached client jar`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        val out = NativeArtifactProducer().produce(
            spec(Side.CLIENT), request(cache, ArtifactKey.CLIENT))
        assertEquals(cache.resolve("versions/1.20.1/client.jar"), out)
        assertEquals(clientJar.toList(), Files.readAllBytes(out).toList())
    }

    @Test
    fun `produce SERVER returns the plain server jar unbundled`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        val out = NativeArtifactProducer().produce(
            spec(Side.SERVER), request(cache, ArtifactKey.SERVER))
        // not a bundler jar (no META-INF/versions/ entry) — the raw
        // download is the artifact
        assertEquals(cache.resolve("versions/1.20.1/server.jar"), out)
        assertEquals(serverJar.toList(), Files.readAllBytes(out).toList())
    }

    @Test
    fun `produce MERGED merges server over client`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        val out = NativeArtifactProducer().produce(
            spec(Side.MERGED), request(cache, ArtifactKey.MERGED))
        ZipFile(out.toFile()).use { zip ->
            assertEquals("client",
                zip.getInputStream(zip.getEntry("client.txt"))
                    .readBytes().decodeToString())
            assertEquals("server",
                zip.getInputStream(zip.getEntry("server.txt"))
                    .readBytes().decodeToString())
            // non-class resource conflicts resolve client-side
            // (JarMerger parity: server fills in client-absent only)
            assertEquals("client",
                zip.getInputStream(zip.getEntry("shared.txt"))
                    .readBytes().decodeToString())
        }
    }

    @Test
    fun `produce MERGED applies preMerge hooks on manifest-server versions`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        var calls = 0
        val out = NativeArtifactProducer().produce(
            spec(Side.MERGED),
            request(cache, ArtifactKey.MERGED, preMerge = { client, server ->
                calls++
                assertEquals(cache.resolve("versions/1.20.1/client.jar"), client)
                assertEquals(cache.resolve("versions/1.20.1/server.jar"), server)
                val patchedClient = dir.resolve("patched-client.jar")
                val patchedServer = dir.resolve("patched-server.jar")
                Files.write(patchedClient, jar(mapOf(
                    "client.txt" to "patched-client",
                    "shared.txt" to "patched-client")))
                Files.write(patchedServer, jar(mapOf(
                    "server.txt" to "patched-server",
                    "shared.txt" to "patched-server")))
                patchedClient to patchedServer
            }))

        assertEquals(1, calls,
            "side-patch hooks must run even when version metadata ships a server download")
        ZipFile(out.toFile()).use { zip ->
            assertEquals("patched-client",
                zip.getInputStream(zip.getEntry("client.txt"))
                    .readBytes().decodeToString())
            assertEquals("patched-server",
                zip.getInputStream(zip.getEntry("server.txt"))
                    .readBytes().decodeToString())
        }
    }

    @Test
    fun `produce COMMON intersects client and server`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        val out = NativeArtifactProducer().produce(
            spec(Side.MERGED), request(cache, ArtifactKey.COMMON))
        ZipFile(out.toFile()).use { zip ->
            // only entries present on BOTH sides — server bytes win
            assertEquals(setOf("shared.txt"),
                zip.entries().asSequence().map { it.name }
                    .filter { !it.endsWith("/") }.toSet())
            assertEquals("server",
                zip.getInputStream(zip.getEntry("shared.txt"))
                    .readBytes().decodeToString())
        }
    }

    @Test
    fun `empty- pseudo-version produces empty jars for every key`() {
        val cache = dir.resolve("cache")
        // no manifest/version-json staged — `empty-*` short-circuits
        // before metadata resolution, so produce must not touch the
        // network at all
        for (key in listOf(
            ArtifactKey.CLIENT, ArtifactKey.SERVER,
            ArtifactKey.MERGED, ArtifactKey.COMMON)) {
            val out = NativeArtifactProducer().produce(
                spec(Side.MERGED, mcVersion = "empty-1.20.1"),
                request(cache, key))
            ZipFile(out.toFile()).use { zip -> assertEquals(0, zip.size()) }
            assertEquals(
                "versions/empty-1.20.1/${key.name.lowercase()}.jar",
                cache.relativize(out).toString().replace('\\', '/'))
        }
    }

    @Test
    fun `offline request serves staged artifacts without fetching`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        // offline=true makes every fetch/fetchMetadata cache-or-throw —
        // staging is complete, so nothing reaches the network
        val client = NativeArtifactProducer().produce(
            spec(Side.CLIENT),
            request(cache, ArtifactKey.CLIENT, offline = true))
        assertEquals(clientJar.toList(), Files.readAllBytes(client).toList())
        val merged = NativeArtifactProducer().produce(
            spec(Side.MERGED),
            request(cache, ArtifactKey.MERGED, offline = true))
        ZipFile(merged.toFile()).use { zip ->
            assertTrue(zip.getEntry("shared.txt") != null)
        }
    }

    @Test
    fun `produce rejects artifact keys without a product`() {
        val cache = dir.resolve("cache")
        stageCache(cache)
        assertThrows(Exception::class.java) {
            NativeArtifactProducer().produce(
                spec(Side.CLIENT), request(cache, ArtifactKey.SOURCES))
        }
    }

}
