package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevSourcesChain
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigDescriptorFormat
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigFunctionSource
import xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredTool
import xyz.wagyourtail.unimined.transform.step.subpipeline.FormatContext
import xyz.wagyourtail.unimined.transform.step.subpipeline.PipelineFunction
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubStep
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineContext
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineExecutor
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * The userdev sources splice-ins — applyAts before decompile,
 * forgeInject after patch, forgePatch tail, patch-prefix rewriting.
 */
class UserdevSourcesChainTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(name: String, entries: Map<String, String>): Path {
        val jar = dir.resolve(name)
        jar.parent.let { java.nio.file.Files.createDirectories(it) }
        JarOutputStream(jar.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(JarEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    /** Exec stub — records calls, writes an empty jar at the arg
     *  following `--out`/`--output` so chained steps see outputs. */
    private fun stubExec(calls: MutableList<List<String>>)
            : (DeclaredTool, List<String>, List<String>, List<Path>) -> Unit =
        { _, args, _, _ ->
            calls.add(args)
            val outIdx = args.indexOfLast {
                it == "--out" || it == "--output" || it == "-o"
            }
            if (outIdx >= 0) {
                val out = Path.of(args[outIdx + 1])
                out.parent?.let { java.nio.file.Files.createDirectories(it) }
                JarOutputStream(out.outputStream()).use {
                    it.putNextEntry(JarEntry("generated.class"))
                    it.closeEntry()
                }
            }
        }

    private fun configZip(steps: String): Path =
        jar(
            "mcp_config.zip", mapOf(
                "config.json" to """{
              "data": {"patches": "patches/"},
              "functions": {
                "rename": {"version": "tool:rename:1", "args": ["--out", "{output}"]},
                "decompile": {"version": "tool:decomp:1", "args": ["--in", "{input}", "--out", "{output}"]}
              },
              "steps": {"client": [
                {"type": "downloadClient"},
                {"type": "rename"},
                {"type": "decompile", "input": "{renameOutput}"},
                {"type": "patch", "input": "{decompileOutput}"}
              ]}
            }"""
            ) + steps.let { emptyMap() })

    private fun run(
        zip: Path,
        calls: MutableList<List<String>>,
        userdev: Path,
        sourcesDir: Path,
        ats: List<Path>,
        applyAts: (input: Path, ats: List<Path>, output: Path) -> Unit,
        cfg: UserdevConfig,
        untilStep: String
    ): Path? {
        val fmt = McpConfigDescriptorFormat()
        var pipeline = fmt.parse(zip.toFile(), object : FormatContext {
            override val side = "client"
            override val bindingNames = setOf(
                "client", "server", "clientMappings", "serverMappings"
            )
        })
        val (spliced, tools) = UserdevSourcesChain.install(
            pipeline, userdev, sourcesDir,
            dir.resolve("forge-cache"), cfg
        )
        pipeline = spliced.copy(functions = spliced.functions + tools)
        val applyAtsFn = object : PipelineFunction {
            override val name = "forge:applyAts"
            override fun run(
                step: SubStep, ctx: SubpipelineContext
            ): Path {
                val input = Path.of(ctx.vars.getValue("input"))
                val output = ctx.workDir.resolve("applyAtsOutput.jar")
                applyAts(input, ats, output)
                return output
            }
        }
        return SubpipelineExecutor(
            pipeline, fmt,
            McpConfigFunctionSource().functions()
                .associateBy { it.name } + (applyAtsFn.name to applyAtsFn),
            bindings = mapOf(
                "client" to {
                    jar("client.jar", mapOf("a.class" to "x")).toFile()
                },
                "server" to { jar("server.jar", emptyMap()).toFile() },
                "clientMappings" to { dir.resolve("cm.txt").toFile() },
                "serverMappings" to { dir.resolve("sm.txt").toFile() },
            ),
            toolResolver = {
                listOf(
                    jar(
                        "tool.jar", mapOf(
                            "META-INF/MANIFEST.MF" to
                                    "Manifest-Version: 1.0\nMain-Class: T\n\n"
                        )
                    )
                )
            },
            listBindings = mapOf("libraries" to { emptyList<File>() }),
            toolExec = stubExec(calls)
        ).execute(
            zip.toFile(), "client", null, dir.resolve("cache"),
            untilStep = untilStep
        )
    }

    private fun userdevCfg(extra: String = "") = UserdevConfig(
        Json.parseToJsonElement("""{"notchObf": false, "patches": "patches"$extra}""")
                as JsonObject
    )

    @Test
    fun `applyAts runs before decompile with renamed input`() {
        val calls = mutableListOf<List<String>>()
        val atsCalls = mutableListOf<Triple<Path, List<Path>, Path>>()
        val userdev = jar("userdev.jar", mapOf("patches/A.java.patch" to "patch"))

        run(
            configZip(""), calls, userdev, dir.resolve("src"),
            listOf(dir.resolve("at.cfg")),
            { input, ats, output ->
                atsCalls.add(Triple(input, ats, output))
                JarOutputStream(output.outputStream()).use { it.close() }
            },
            userdevCfg(), untilStep = "forgePatch"
        )
        assertEquals(1, atsCalls.size)
        // applyAts consumed the rename step's output jar
        assertEquals(dir.resolve("cache/rename/renameOutput.jar"), atsCalls[0].first)
    }

    @Test
    fun `forgeInject merges sources into patched jar`() {
        val calls = mutableListOf<List<String>>()
        val userdev = jar("userdev.jar", emptyMap())
        val sourcesDir = dir.resolve("src")
        java.nio.file.Files.createDirectories(sourcesDir)
        val forgeSrc = sourcesDir.resolve("net/minecraftforge/Y.java")
        java.nio.file.Files.createDirectories(forgeSrc.parent)
        java.nio.file.Files.writeString(forgeSrc, "// forge")

        val result = run(
            configZip(""), calls, userdev, sourcesDir,
            emptyList(), { _, _, o ->
                JarOutputStream(o.outputStream()).use { it.close() }
            },
            userdevCfg(), untilStep = "forgeInject"
        )
        JarFile(result!!.toFile()).use { j ->
            val names = j.entries().asSequence().map { it.name }.toSet()
            assertTrue("net/minecraftforge/Y.java" in names)
        }
    }

    @Test
    fun `patchSetJar rewrites non-ab prefixes`() {
        val userdev = jar(
            "userdev.jar", mapOf(
                "patches/minecraft/World.java.patch" to
                        "--- ORIG/World.java\n+++ MOD/World.java\n@@ body\n",
                "other.txt" to "not-a-patch"
            )
        )
        val cfg = UserdevConfig(
            Json.parseToJsonElement(
                """{"notchObf": false, "patchesOriginalPrefix": "ORIG/",
             "patchesModifiedPrefix": "MOD/"}"""
            ) as JsonObject
        )
        val out = UserdevSourcesChain.patchSetJar(userdev, dir, cfg)
        JarFile(out.toFile()).use { j ->
            val patch = j.getInputStream(
                j.getJarEntry("patches/minecraft/World.java.patch")
            )
                .readBytes().decodeToString()
            assertEquals("--- a/World.java\n+++ b/World.java\n@@ body\n\n", patch)
            // non-patch entries not carried over
            assertEquals(null, j.getJarEntry("other.txt"))
        }
    }

    @Test
    fun `patchSetJar passes userdev jar through for default prefixes`() {
        val userdev = jar("userdev.jar", emptyMap())
        assertEquals(
            userdev, UserdevSourcesChain.patchSetJar(
                userdev, dir, userdevCfg()
            )
        )
    }
}
