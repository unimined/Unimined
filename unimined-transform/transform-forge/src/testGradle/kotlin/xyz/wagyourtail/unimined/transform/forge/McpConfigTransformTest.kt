package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigDescriptorFormat
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigFunctionSource
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.cleanJarTargetStep
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.mcpConfigCacheKey
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.sideCacheKey
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.withToolOverride
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredSubpipeline
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredTool
import xyz.wagyourtail.unimined.transform.step.subpipeline.FormatContext
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubStep
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineExecutor
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

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

    /**
     * MCPConfig 1.13–1.16 inserts `mcinject` between `rename` and
     * `decompile`. That instrumentation is decompilation-only: Forge's
     * joined binpatches checksum the `rename` output (verified against
     * 1.16.5-36.2.42, whose `WorldBorder$Serializer` input checksum is
     * the rename bytes, not the mcinject bytes).
     */
    @Test
    fun `clean jar target is rename when mcinject feeds decompile`() {
        val pipeline = DeclaredSubpipeline(
            steps = listOf(
                SubStep("merge", "merge"),
                SubStep("rename", "rename", mapOf("input" to "{mergeOutput}")),
                SubStep("mcinject", "mcinject", mapOf("input" to "{renameOutput}")),
                SubStep("decompile", "decompile", mapOf("input" to "{mcinjectOutput}"))
            )
        )
        assertEquals(
            "rename",
            cleanJarTargetStep(pipeline, "joined", notchObf = false),
            "obfuscated userdev binpatches must not consume mcinject output")
    }

    /** notchObf-era packs checksum the pre-rename merge/strip output. */
    @Test
    fun `clean jar target is rename input for notchObf`() {
        val pipeline = DeclaredSubpipeline(
            steps = listOf(
                SubStep("merge", "merge"),
                SubStep("rename", "rename", mapOf("input" to "{mergeOutput}")),
                SubStep("decompile", "decompile", mapOf("input" to "{renameOutput}"))
            )
        )
        assertEquals(
            "merge",
            cleanJarTargetStep(pipeline, "joined", notchObf = true))
    }

    /** Unobfuscated 26.x descriptors have no rename boundary; the
     *  decompile input is already the binary chain tail. */
    @Test
    fun `clean jar target falls back to decompile input without rename`() {
        val pipeline = DeclaredSubpipeline(
            steps = listOf(
                SubStep("merge", "merge"),
                SubStep("decompile", "decompile", mapOf("input" to "{mergeOutput}"))
            )
        )
        assertEquals(
            "merge",
            cleanJarTargetStep(pipeline, "joined", notchObf = false))
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

    /** #224 — NeoForm 26.x descriptors carry only `steps.joined`.
     *  Split-side envs must fall back to that chain (MDG always runs
     *  `--dist joined`); a missing requested side is only an error when
     *  no joined chain exists either. */
    @Test
    fun `split side falls back to joined steps when the descriptor has no side chain`(
        @TempDir dir: Path
    ) {
        val descriptor = dir.resolve("neoform-26.3-1.zip").toFile()
        JarOutputStream(descriptor.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("config.json"))
            jar.write(
                """
                {
                  "spec": 6, "version": "26.3",
                  "data": { "patches": "patches/" },
                  "steps": { "joined": [
                    { "type": "downloadClient" },
                    { "type": "downloadServer" }
                  ]},
                  "functions": {}, "libraries": []
                }
                """.trimIndent().toByteArray())
            jar.closeEntry()
        }
        val pipeline = McpConfigDescriptorFormat().parse(
            descriptor,
            object : FormatContext {
                override val side = "server"
                override val bindingNames = setOf("client", "server")
            }
        )
        assertEquals(
            listOf("downloadClient", "downloadServer"),
            pipeline.steps.map { it.name },
            "a server env must consume the joined chain when no " +
                    "steps.server section exists")
    }

    /**
     * Spec ≥6 (26.x neoform) descriptors decorate the steps we replace
     * with in-process bindings — `downloadJson`,
     * `downloadClient`/`downloadServer`, `listLibraries` — with
     * `json: {xOutput}` args the bound functions never read. Eager
     * arg interpolation still resolves those refs against the noop'd
     * `downloadManifest`/`downloadJson` outputs, which are unrecorded
     * (null) — previously dying with
     * `No output from step 'downloadManifest'` on `neoform:26.3-1`.
     * Args on descriptor-replaced steps are decorative; the parser
     * must drop them.
     */
    @Test
    fun `spec6 noop-chained args on bound steps do not break interpolation`(
        @TempDir dir: Path
    ) {
        val descriptor = dir.resolve("neoform-26.3-1.zip").toFile()
        JarOutputStream(descriptor.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("config.json"))
            jar.write(
                """
                {
                  "spec": 6, "version": "26.3",
                  "data": { "patches": "patches/" },
                  "steps": { "joined": [
                    { "type": "downloadManifest" },
                    { "type": "downloadJson",
                      "json": "{downloadManifestOutput}" },
                    { "type": "downloadClient",
                      "json": "{downloadJsonOutput}" },
                    { "type": "downloadServer",
                      "json": "{downloadJsonOutput}" },
                    { "type": "listLibraries",
                      "json": "{downloadJsonOutput}" },
                    { "type": "merge",
                      "client": "{downloadClientOutput}",
                      "server": "{downloadServerOutput}" }
                  ]},
                  "functions": {
                    "merge": { "version": "g:a:1", "args": [] }
                  },
                  "libraries": []
                }
                """.trimIndent().toByteArray())
            jar.closeEntry()
        }

        val pipeline = McpConfigDescriptorFormat().parse(
            descriptor,
            object : FormatContext {
                override val side = "joined"
                override val bindingNames = setOf("client", "server")
            }
        )
        // the noop'd steps record no output — bound-step args must not
        // retain {xOutput} refs into them
        val boundSteps = pipeline.steps.filter {
            it.function.startsWith("mcpconfig:")
        }
        assertTrue(boundSteps.isNotEmpty())
        for (step in boundSteps) {
            assertTrue(
                step.args.values.none { "{download" in it },
                "bound step '${step.name}' retains an unresolved " +
                        "reference: ${step.args}")
        }

        // execution must reach the bound steps — previously it died
        // interpolating downloadJson's json arg before any function ran
        val clientJar = dir.resolve("client.jar").toFile()
            .apply { writeText("client") }
        val serverJar = dir.resolve("server.jar").toFile()
            .apply { writeText("server") }
        val out = SubpipelineExecutor(
            pipeline,
            McpConfigDescriptorFormat(),
            McpConfigFunctionSource().functions().associateBy { it.name },
            bindings = mapOf("client" to { clientJar },
                "server" to { serverJar }),
            toolResolver = { emptyList() },
        ).execute(descriptor, "joined", null,
            dir.resolve("work"), untilStep = "listLibraries")
        assertNotNull(out, "listLibraries must produce its -e= list")
        assertTrue(out!!.toFile().isFile)
    }
}
