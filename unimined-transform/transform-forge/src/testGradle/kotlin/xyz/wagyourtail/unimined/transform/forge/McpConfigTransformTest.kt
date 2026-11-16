package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.mcpConfigCacheKey
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.sideCacheKey
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.withToolOverride
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredSubpipeline
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredTool
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubStep
import java.nio.file.Path

/** The subpipeline work-dir key must be keyed on descriptor *content*
 *  — the `exists()` substep cache would otherwise serve outputs
 *  produced from a different artifact that shares a filename. */
class McpConfigTransformTest {

    @Test
    fun `cache key follows content, not filename`(@TempDir dir: Path) {
        // same filename in sibling dirs, different content —
        // the cache dirs must not collide
        val a = dir.resolve("a/mcp_config-1.20.1.zip").toFile()
            .apply { parentFile.mkdirs(); writeText("descriptor-A") }
        val b = dir.resolve("b/mcp_config-1.20.1.zip").toFile()
            .apply { parentFile.mkdirs(); writeText("descriptor-B") }
        assertNotEquals(mcpConfigCacheKey(a), mcpConfigCacheKey(b),
            "same-named files with different content must not share a cache dir")
    }

    @Test
    fun `cache key is deterministic and keeps a readable name prefix`(
        @TempDir dir: Path
    ) {
        val zip = dir.resolve("mcp_config-1.20.1.zip").toFile()
            .apply { writeText("descriptor-A") }
        val key = mcpConfigCacheKey(zip)
        assertEquals(key, mcpConfigCacheKey(zip))
        assertTrue(
            key.startsWith("mcp_config-1.20.1-"),
            "key should keep the sanitized filename as a prefix: $key")
        assertTrue(
            Regex("[^A-Za-z0-9._-]").find(key) == null,
            "key must stay fs-safe: $key")
        // identical content under a different name shares the content
        // hash suffix — only the readability prefix differs
        val renamed = dir.resolve("renamed.zip").toFile()
            .apply { writeText("descriptor-A") }
        assertEquals(
            key.substringAfterLast('-'),
            mcpConfigCacheKey(renamed).substringAfterLast('-'))
    }

    /**
     * B6.9 — the `exists()` substep gate is content-blind: a side dir
     * shared across effective tool coords would serve a `renameOutput`
     * built by the wrong FART version (descriptor-declared vs
     * installer-chain), re-breaking `data/<side>.lzma` checksums.
     */
    @Test
    fun `side cache key salts the rename tool override`() {
        // no override → the plain side dir (joined/env behavior intact)
        assertEquals("server", sideCacheKey("server", null))
        val fart0122 = "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all"
        val fart102 = "net.minecraftforge:ForgeAutoRenamingTool:1.0.2:all"
        val salted = sideCacheKey("server", fart0122)
        assertTrue(salted.startsWith("server-"), "keeps side prefix: $salted")
        assertNotEquals("server", salted)
        // deterministic + coord-sensitive + fs-safe
        assertEquals(salted, sideCacheKey("server", fart0122))
        assertNotEquals(salted, sideCacheKey("server", fart102))
        assertNotEquals(salted, sideCacheKey("client", fart0122))
        assertNull(Regex("[^A-Za-z0-9._-]").find(salted), "fs-safe: $salted")
    }

    /** `withToolOverride` replaces only the named function's coords. */
    @Test
    fun `withToolOverride swaps only the named function coords`() {
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("rename", "rename")),
            functions = mapOf(
                "rename" to DeclaredTool(
                    listOf("net.minecraftforge:ForgeAutoRenamingTool:1.0.2:all"),
                    args = listOf("--input", "{input}"),
                    javaVersion = 17
                ),
                "merge" to DeclaredTool(
                    listOf("net.minecraftforge:mergetool:1.1.7:fatjar"))
            )
        )
        val out = pipeline.withToolOverride(
            "rename", "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all")
        assertEquals(
            listOf("net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all"),
            out.functions.getValue("rename").coords)
        // the rest of the declared tool survives the coord swap
        assertEquals(listOf("--input", "{input}"),
            out.functions.getValue("rename").args)
        assertEquals(17, out.functions.getValue("rename").javaVersion)
        assertEquals(listOf("net.minecraftforge:mergetool:1.1.7:fatjar"),
            out.functions.getValue("merge").coords)
        assertEquals(pipeline.steps, out.steps)
        // absent function (non-mcpconfig descriptor shapes) → no-op
        assertSame(pipeline, pipeline.withToolOverride("nope", "g:a:1"))
    }
}
