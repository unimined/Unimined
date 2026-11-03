package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredSubpipeline
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredTool
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubStep
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig

/**
 * The FG3-6 userdev *sources* splice-ins — 1.x `FG3MinecraftTransformer`
 * `mcpConfigRunner.also {}` block. Installs, onto a configured
 * [McpConfigRunner]:
 *
 *  - `applyAts` (before `decompile`) — AT tool over the renamed jar;
 *    decompile's `input` var rebased to its output
 *  - `updateCacheDir` (before `applyAts`) — repoint the cache for
 *    downstream steps
 *  - `forgeInject` (after `patch`) — inject the userdev `:sources`
 *    extraction into the patched jar's source tree
 *  - `mcpCleanup` (before `forgeInject`, `notchObf` only) — legacy
 *    MCPCleanup pass
 *  - `forgePatch` (after `forgeInject`) — DiffPatch over the userdev
 *    `patches` set (prefixes rewritten when `patchesOriginalPrefix`/
 *    `patchesModifiedPrefix` differ from `a/`/`b/`)
 *
 * [McpConfigRunner.resultFor]`("forgePatch")` then yields the dev
 * sources jar.
 */
internal object UserdevSourcesChain {

    /**
     * @param userdevJar the resolved userdev installer jar (carries
     *  `patches/` etc.)
     * @param sourcesDir extracted `*:sources` classifier contents
     *  ([UserdevExtract.extractSources])
     * @param atFiles resolved AT files applied before decompile
     * @param applyAts the AT-tool invocation — `(input, ats, output)`
     * @param cacheDir the post-`updateCacheDir` scratch root
     * @param config the userdev `config.json` interpretation
     */
    /**
     * @param pipeline the parsed mcpconfig graph — returned spliced
     *  with the sources steps (`applyAts` before `decompile`,
     *  `forgeInject`/`mcpCleanup`/`forgePatch` appended)
     * @return the spliced pipeline plus extra functions to merge into
     *  the executor's function map and extra tool-table entries to
     *  append to `pipeline.functions`
     */
    fun install(
        pipeline: DeclaredSubpipeline,
        userdevJar: Path,
        sourcesDir: Path,
        cacheDir: Path,
        config: UserdevConfig
    ): Pair<DeclaredSubpipeline, Map<String, DeclaredTool>> {
        val steps = pipeline.steps.toMutableList()
        val tools = mutableMapOf<String, DeclaredTool>()

        // applyAts before decompile; decompile's input rebases to it
        val decompileIdx = steps.indexOfFirst { it.name == "decompile" }
        require(decompileIdx >= 0) { "no decompile step in mcpconfig" }
        steps.add(
            decompileIdx,
            SubStep(
                "applyAts", "forge:applyAts",
                mapOf("input" to "{renameOutput}")
            )
        )
        steps[decompileIdx + 1] = steps[decompileIdx + 1].copy(
            args = steps[decompileIdx + 1].args +
                    ("input" to "{applyAtsOutput}")
        )

        // forgeInject merges the userdev :sources dir into the jar
        val injectInput = if (config.notchObf) {
            steps.add(
                steps.indexOfFirst { it.name == "forgeInject" }
                    .takeIf { it >= 0 } ?: steps.size,
                SubStep(
                    "mcpCleanup", "forge:mcpCleanup",
                    mapOf("input" to "{patchOutput}")
                )
            )
            tools["forge:mcpCleanup"] = DeclaredTool(
                "net.minecraftforge:mcpcleanup:2.3.6", null,
                listOf("--input", "{input}", "--output", "{output}"),
                emptyList()
            )
            "{mcpCleanupOutput}"
        } else "{patchOutput}"
        steps.add(
            SubStep(
                "forgeInject", "mcpconfig:inject",
                mapOf(
                    "input" to injectInput,
                    "inject" to sourcesDir.absolutePathString()
                )
            )
        )
        steps.add(
            SubStep(
                "forgePatch", "mcpconfig:diffpatch",
                mapOf(
                    "input" to "{forgeInjectOutput}",
                    "patches" to patchSetJar(userdevJar, cacheDir, config)
                        .absolutePathString(),
                    "prefix" to (config.patches ?: "patches"),
                    "reject" to "{output}.reject.jar"
                )
            )
        )
        tools.putIfAbsent(
            "mcpconfig:diffpatch",
            DeclaredTool(
                "net.minecraftforge:DiffPatch:2.0.12",
                args = listOf(
                    "-p", "-m", "FUZZY", "-P", "{prefix}",
                    "-A", "ZIP", "-o", "{output}",
                    "-r", "{reject}", "-H", "ZIP", "-s",
                    "-v", "{input}", "{patches}"
                )
            )
        )
        return pipeline.copy(steps = steps) to tools
    }

    /**
     * The patch archive handed to `forgePatch` — the userdev jar
     * verbatim unless `patchesOriginalPrefix`/`patchesModifiedPrefix`
     * differ from `a/`/`b/`, in which case `.patch` headers are
     * rewritten into a cache jar (1.x parity).
     */
    fun patchSetJar(userdevJar: Path, cacheDir: Path, config: UserdevConfig): Path {
        val origin = config.patchesOriginalPrefix
        val modified = config.patchesModifiedPrefix
        if (origin == "a/" && modified == "b/") return userdevJar

        val output = cacheDir.resolve("forgePatches.jar")
        JarOutputStream(output.outputStream()).use { out ->
            JarFile(userdevJar.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".patch") }
                    .forEach { entry ->
                        out.putNextEntry(JarEntry(entry.name))
                        val text = jar.getInputStream(entry).readBytes()
                            .decodeToString().lines().joinToString("\n") { line ->
                                when {
                                    line.startsWith("--- $origin") ->
                                        "--- a/" + line.removePrefix("--- $origin")

                                    line.startsWith("+++ $modified") ->
                                        "+++ b/" + line.removePrefix("+++ $modified")

                                    else -> line
                                }
                            } + "\n"
                        out.write(text.toByteArray())
                        out.closeEntry()
                    }
            }
        }
        return output
    }
}
