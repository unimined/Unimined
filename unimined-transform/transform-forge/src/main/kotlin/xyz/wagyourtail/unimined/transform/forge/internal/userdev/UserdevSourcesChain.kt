package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
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
    fun install(
        runner: McpConfigRunner,
        userdevJar: Path,
        sourcesDir: Path,
        atFiles: List<Path>,
        applyAts: (input: Path, ats: List<Path>, output: Path) -> Unit,
        cacheDir: Path,
        config: UserdevConfig
    ) {
        runner.insertBefore(
            "decompile",
            McpConfigRunner.FileProviderStep("applyAts", null, mutableMapOf()) { dir ->
                val input = Paths.get(variables.getValue("input").invoke())
                val output = dir.resolve("applyAtsOutput.jar")
                applyAts(input, atFiles, output)
                output
            },
            mapOf("input" to {
                runner.resultFor("applyAts").output!!.absolutePathString()
            })
        )

        runner.insertBefore(
            "applyAts",
            runner.UpdateCacheDirStep("updateCacheDir", null, cacheDir.createDirectories()),
            emptyMap()
        )

        runner.addStep(
            McpConfigRunner.InjectStep(
                "forgeInject", "patch", mutableMapOf(
                    "input" to {
                        runner.resultFor("patch").output!!.absolutePathString()
                    },
                    "inject" to { sourcesDir.absolutePathString() }
                )
            )
        )

        if (config.notchObf) {
            runner.insertBefore(
                "forgeInject",
                runner.FunctionStep(
                    "mcpCleanup", null, mutableMapOf(),
                    McpConfigRunner.Function(
                        "net.minecraftforge:mcpcleanup:2.3.6", null,
                        listOf("--input", "{input}", "--output", "{output}"),
                        emptyList()
                    )
                ),
                mapOf("input" to {
                    runner.resultFor("mcpCleanup").output!!.absolutePathString()
                })
            )
        }

        runner.addStep(
            runner.PatchStep(
                "forgePatch", "forgeInject", mutableMapOf(
                    "input" to {
                        runner.resultFor("forgeInject").output!!.absolutePathString()
                    },
                    "patches" to {
                        patchSetJar(userdevJar, cacheDir, config).absolutePathString()
                    },
                    "prefix" to { config.patches ?: "patches" }
                )
            )
        )
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
