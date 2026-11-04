package xyz.wagyourtail.unimined.init

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.net.URI

/**
 * Resolver parsing against real payload shapes — fetch is injected so
 * no network touches the test.
 */
class VersionResolverTest {

    private fun resolver(payloads: Map<String, String>) = VersionResolver(
        fetch = { uri -> payloads[uri.toString()] ?: error("unstubbed $uri") }
    )

    @Test
    fun `offline falls back to fresh cache, stale cache errors`(
        @org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path
    ) {
        val uri = VersionResolver.PISTON_META
        val body = """{"latest":{"release":"1.21.4"}}"""
        java.nio.file.Files.createDirectories(dir)
        dir.resolve("version-resolver.json").toFile().writeText(
            VersionResolver.serializeCache(
                mapOf(uri.toString() to (System.currentTimeMillis() to body))
            )
        )
        // offline: fetch throws, fresh cache serves
        val offline = VersionResolver(
            fetch = { throw java.io.IOException("offline") },
            cacheDir = dir.toString()
        )
        assertEquals("1.21.4", offline.latestMinecraft())

        // stale entry falls through to the build-time baked floor
        dir.resolve("version-resolver.json").toFile().writeText(
            VersionResolver.serializeCache(
                mapOf(uri.toString() to (0L to body))
            )
        )
        assertEquals(
            xyz.wagyourtail.unimined.init.Probed.expand("@mc").first(),
            VersionResolver(
                fetch = { throw java.io.IOException("offline") },
                cacheDir = dir.toString()
            ).latestMinecraft()
        )
    }

    @Test
    fun `latest minecraft from piston-meta`() {
        val r = resolver(mapOf(
            VersionResolver.PISTON_META.toString() to
                    """{"latest":{"release":"1.21.4","snapshot":"25w02a"},"versions":[]}"""
        ))
        assertEquals("1.21.4", r.latestMinecraft())
    }

    @Test
    fun `fabric loader version from fabric-meta`() {
        val uri = "https://meta.fabricmc.net/v2/versions/loader/1.20.1"
        val r = resolver(mapOf(uri to """
            [{"loader":{"separator":".","build":10,"maven":"net.fabricmc:fabric-loader:0.16.0","version":"0.16.0","stable":true}}]
        """.trimIndent()))
        assertEquals("0.16.0", r.latestLoader("fabric", "1.20.1"))
    }

    @Test
    fun `forge version scoped by mc prefix from maven-metadata`() {
        val xml = """<metadata><versioning><versions>
            <version>1.20-46.0.0</version>
            <version>1.20.1-47.1.0</version>
            <version>1.20.1-47.2.0</version>
            </versions><latest>1.20.1-47.2.0</latest></versioning></metadata>"""
        val r = resolver(mapOf(VersionProbe.FORGE_MAVEN.toString() to xml))
        assertEquals("1.20.1-47.2.0", r.latestLoader("forge", "1.20.1"))
    }

    @Test
    fun `neoforge latest from maven-metadata`() {
        val xml = """<metadata><versioning><versions>
            <version>20.4.1-beta</version><version>21.0.0-beta</version>
            </versions><latest>21.0.0-beta</latest></versioning></metadata>"""
        val r = resolver(mapOf(VersionProbe.NEOFORGE_MAVEN.toString() to xml))
        assertEquals("21.0.0-beta", r.latestLoader("neoforge", "x"))
    }

}

class ProbedExpandTest {

    /** Compressed `base{hi}-{lo}` runs expand newest-first. */
    @org.junit.jupiter.api.Test
    fun `compressed runs expand descending`() {
        // reach into the emitted expander via a synthetic key is not
        // possible; assert through the public expand on a known baked key
        val mcp = Probed.expand("mcp:1.12.2")
        assertEquals("stable_39", mcp.first())
    }

    /** Literal versions must pass through untouched — `find`-style
     *  substring matching would corrupt these. */
    @org.junit.jupiter.api.Test
    fun `literal versions pass through`() {
        // forge version containing a dash-range that looks compressed
        val forge = Probed.expand("forge")
        org.junit.jupiter.api.Assertions.assertTrue(
            "1.20.1-47.4.28" in forge,
            "dash-version literal corrupted: " + forge.take(3).toString())
        // snapshot literals like 26w14a never parse as runs
        org.junit.jupiter.api.Assertions.assertTrue(
            Probed.expand("@mc").all { it.isNotEmpty() })
    }
}
