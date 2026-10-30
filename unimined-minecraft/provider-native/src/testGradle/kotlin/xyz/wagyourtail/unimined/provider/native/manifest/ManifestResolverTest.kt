package xyz.wagyourtail.unimined.provider.native.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Path

class ManifestResolverTest {

    @TempDir
    lateinit var dir: Path

    private val manifestJson = """
        {
          "latest": {"release": "1.20.1", "snapshot": "23w13a"},
          "versions": [
            {"id": "1.20.1", "type": "release",
             "url": "https://example/1.20.1.json",
             "time": "t", "releaseTime": "t"},
            {"id": "b1.7.3", "type": "old_beta",
             "url": "https://example/b1.7.3.json",
             "time": "t", "releaseTime": "t"}
          ]
        }
    """.trimIndent()

    private val versionJson = """
        {
          "id": "1.20.1",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "downloads": {
            "client": {"url": "https://example/client.jar",
                       "sha1": "abc", "size": 100},
            "server": {"url": "https://example/server.jar",
                       "sha1": "def", "size": 200}
          },
          "assetIndex": {"url": "https://example/index.json",
                         "id": "5", "sha1": "x", "size": 1},
          "libraries": [{"name": "com.example:lib:1.0"}]
        }
    """.trimIndent()

    @Test
    fun `manifest resolves version by id`() {
        val r = resolver()
        assertEquals("1.20.1", r.manifest().latest.release)
        assertEquals(
            "https://example/1.20.1.json",
            r.manifest().version("1.20.1").url
        )
        assertThrows<IllegalArgumentException> {
            r.manifest().version("9.9")
        }
    }

    @Test
    fun `versionJson parses downloads and metadata`() {
        var calls = 0
        val r = resolver { url ->
            calls++
            if (url.contains("version_manifest")) manifestJson else versionJson
        }
        val v = r.versionJson("1.20.1")
        val downloads = v.downloads!!
        assertEquals("https://example/client.jar", downloads["client"]!!.url)
        assertEquals("def", downloads["server"]!!.sha1)
        assertEquals("5", v.assetIndex!!.id)
        assertEquals("net.minecraft.client.main.Main", v.mainClass)
        assertEquals("com.example:lib:1.0", v.libraries[0].name)
        r.versionJson("1.20.1")
        assertEquals(2, calls)
    }

    @Test
    fun `alternate manifest resolves versions mojang lacks`() {
        val altManifest = """
            {
              "latest": {"release": "1.20.1", "snapshot": "23w13a"},
              "versions": [
                {"id": "a1.1.0", "type": "old_alpha",
                 "url": "https://alt.example/a1.1.0.json",
                 "details": "https://alt.example/a1.1.0-details.json",
                 "time": "t", "releaseTime": "t"}
              ]
            }
        """.trimIndent()
        val alphaJson = """{"id": "a1.1.0", "type": "old_alpha",
            |"mainClass": "net.minecraft.client.main.Main"}""".trimMargin()
        val detailsJson = """{"extraField": "from-details",
            |"mainClass": "details-must-not-override"}""".trimMargin()
        val r = ManifestResolver(
            Downloader(dir, fetch = { url ->
                when {
                    url.contains("alt.example/a1.1.0-details") ->
                        detailsJson.toByteArray()
                    url.contains("alt.example/a1.1.0") ->
                        alphaJson.toByteArray()
                    url.contains("alt.example") -> altManifest.toByteArray()
                    else -> manifestJson.toByteArray()
                }
            }),
            manifestUrls = listOf(
                ManifestResolver.VERSION_MANIFEST_V2,
                "https://alt.example/version_manifest.json"
            )
        )
        val doc = r.versionJsonDocument("a1.1.0")
        assertEquals("a1.1.0", doc["id"]!!.toString().trim('"'))
        // details fills keys the standard doc lacks but never overrides
        assertEquals("from-details", doc["extraField"]!!.toString().trim('"'))
        assertEquals("net.minecraft.client.main.Main",
            doc["mainClass"]!!.toString().trim('"'))
    }

    @Test
    fun `manifest and version json resolve from cache offline`() {
        val onlineResolver = resolver { url ->
            if (url.contains("version_manifest")) manifestJson else versionJson
        }
        onlineResolver.versionJson("1.20.1")

        val offlineResolver = ManifestResolver(
            Downloader(
                cacheDir = dir,
                offline = true,
                fetch = { error("offline resolver must not fetch") }
            )
        )
        assertEquals("1.20.1", offlineResolver.versionJson("1.20.1").id)
    }

    private fun resolver(fetch: (String) -> String = { manifestJson }): ManifestResolver =
        ManifestResolver(
            Downloader(dir, fetch = { url -> fetch(url).toByteArray() })
        )

}
