package xyz.wagyourtail.unimined.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end pins for the CLI's per-loader mapping defaults — the
 * commands generate fully offline (`--offline` selects the baked probe
 * lists), so the emitted `mappings {}` lines are deterministic.
 */
class CliGenerateTest {

    /**
     * 1.7.10-era LiteLoader has no `mcp_config` artifact — its srg/mcp
     * data rides the FG1-era forge `:src` zip, so `defaultMappings` must
     * emit `forgeBuiltinMCP` (not the `searge()`+`mcp()` pair 1.12.2
     * gets). The build string is forge's newest `1.7.10-*` build.
     */
    @Test
    fun `liteloader 1_7_10 emits the FG1-era forgeBuiltinMCP default`() {
        val dir = Files.createTempDirectory("ll1710")
        val result = New().test(
            "ll1710", "--out", dir.toString(),
            "--mc", "1.7.10", "--loader", "liteloader=1.7.10_04",
            "--group", "com.example",
            "--plugin-version", "2.0.0",
            "--offline", "--no-wrapper")
        assertEquals(0, result.statusCode, result.output)
        val build = dir.resolve("build.gradle.kts")
        assertTrue(build.exists(), "generated: ${dir.listDirectoryEntries()}")
        assertTrue(
            "forgeBuiltinMCP(\"10.13.4.1614-1.7.10\")" in build.readText(),
            build.readText())
    }

    /** The mcp_config era boundary — 1.12.2 keeps `searge()`+`mcp()`. */
    @Test
    fun `liteloader 1_12_2 keeps the searge+mcp default`() {
        val dir = Files.createTempDirectory("ll1122")
        val result = New().test(
            "ll1122", "--out", dir.toString(),
            "--mc", "1.12.2", "--loader", "liteloader=1.12.2-SNAPSHOT",
            "--group", "com.example",
            "--plugin-version", "2.0.0",
            "--offline", "--no-wrapper")
        assertEquals(0, result.statusCode, result.output)
        val build = dir.resolve("build.gradle.kts")
        assertTrue(build.exists(), "generated: ${dir.listDirectoryEntries()}")
        val text = build.readText()
        assertTrue("searge()" in text, text)
        assertTrue(Regex("mcp\\(").containsMatchIn(text), text)
        assertTrue("forgeBuiltinMCP" !in text, text)
    }
}
