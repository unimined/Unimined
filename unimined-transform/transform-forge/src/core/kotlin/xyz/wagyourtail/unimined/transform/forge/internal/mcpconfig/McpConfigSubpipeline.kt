package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.buffer
import okio.source
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.step.subpipeline.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.*

/**
 * `mcpconfig` descriptor format + in-process function pack —
 * parses an mcp_config/neoform `config.json` (`steps.<side>`,
 * `data`, `functions`) into a [DeclaredSubpipeline] for
 * [SubpipelineExecutor]; `McpConfigRunner` remains the bespoke path
 * until consumers rewire onto `unimined:subpipeline`.
 *
 * Bindings the executor expects the spec to declare:
 * `client`, `server`, `clientMappings`, `serverMappings`
 * (each `() -> File`), plus `toolResolver` for `functions.*`
 * tool coords.
 */
internal class McpConfigDescriptorFormat : PipelineDescriptorFormat {

    override val name = "mcpconfig"

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(descriptor: File, ctx: FormatContext): DeclaredSubpipeline {
        JarFile(descriptor).use { jar ->
            val config = jar.getInputStream(jar.getJarEntry("config.json")).use {
                json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
            }
            val side = ctx.side ?: "client"
            val data = config["data"]!!.jsonObject.mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonObject)
                    ?.get(side)?.jsonPrimitive?.content
                    ?: v.jsonPrimitive.content
            }
            val functions = config["functions"]!!.jsonObject.mapValues { (n, f) ->
                val o = f.jsonObject
                DeclaredTool(
                    o["version"]!!.jsonPrimitive.content,
                    args = UserdevConfig.flattenArgs(o["args"]),
                    jvmArgs = UserdevConfig.flattenArgs(o["jvmargs"]),
                    javaVersion = o["java_version"]?.jsonPrimitive?.content
                        ?.toIntOrNull()
                )
            }.toMutableMap()

            val stepsJson = config["steps"]!!.jsonObject[side]
                ?: error("mcpconfig has no steps for side '$side'")
            val steps = stepsJson.jsonArray.map { el ->
                val o = el.jsonObject
                val type = o["type"]!!.jsonPrimitive.content
                val name = o["name"]?.jsonPrimitive?.content ?: type
                val args = o.entries
                    .filter { it.key != "type" && it.key != "name" }
                    .associate { (k, v) -> k to v.jsonPrimitive.content }
                    .toMutableMap()
                when (type) {
                    "downloadManifest", "downloadJson" ->
                        SubStep(name, "mcpconfig:noop", args)
                    "downloadClient" -> SubStep(name, "mcpconfig:clientJar", args)
                    "downloadServer" -> SubStep(name, "mcpconfig:serverJar", args)
                    "downloadClientMappings" ->
                        SubStep(name, "mcpconfig:clientMappings", args)
                    "downloadServerMappings" ->
                        SubStep(name, "mcpconfig:serverMappings", args)
                    "bundleExtractJar" -> SubStep(name, "mcpconfig:inputPath", args)
                    "listLibraries" -> SubStep(name, "mcpconfig:listLibraries", args)
                    "strip" -> {
                        args["mappings"] = "{mappings}"
                        SubStep(name, "mcpconfig:strip", args)
                    }
                    "inject" -> {
                        args["inject"] = "{inject}"
                        SubStep(name, "mcpconfig:inject", args)
                    }
                    "patch" -> {
                        functions.putIfAbsent(
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
                        args["patches"] = descriptor.absolutePath
                        args["prefix"] = "{patches}"
                        args["reject"] = "{output}.reject.jar"
                        SubStep(name, "mcpconfig:diffpatch", args)
                    }
                    in functions.keys -> SubStep(name, type, args)
                    else -> error("Unknown mcpconfig step type: $type")
                }
            }
            return DeclaredSubpipeline(steps, data, functions)
        }
    }

    /** `data[key]` prefix extraction — descriptor-zip entries under
     *  the prefix land in `<workDir>/_data/`; the extracted root is
     *  returned (a file when the prefix names one). */
    override fun extractData(
        descriptor: File, key: String,
        vars: Map<String, String>, workDir: Path
    ): Path = extractPrefix(descriptor, vars[key], workDir)
}

private fun extractPrefix(descriptor: File, prefix: String?, workDir: Path): Path {
    prefix ?: error("Unknown data key")
    val root = workDir.resolve("_data")
    JarFile(descriptor).use { jar ->
        jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(prefix) }
            .forEach { entry ->
                val target = root.resolve(entry.name)
                target.parent.createDirectories()
                jar.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
    }
    return root.resolve(prefix)
}

private class BindingFunction(
    override val name: String,
    private val binding: String
) : PipelineFunction {
    override fun run(step: SubStep, ctx: SubpipelineContext): Path? =
        ctx.binding(binding)?.toPath()
}

private object NoOpFunction : PipelineFunction {
    override val name = "mcpconfig:noop"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path? = null
}

private object InputPathFunction : PipelineFunction {
    override val name = "mcpconfig:inputPath"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path? =
        Paths.get(ctx.vars.getValue("input"))
}

/** `-e=<jar>` rows for the env classpath (tool `-e` library list) —
 *  `libraries` list-binding, or the remap classpath when the step
 *  runs inside a remap pipeline. */
private object ListLibrariesFunction : PipelineFunction {
    override val name = "mcpconfig:listLibraries"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path {
        val output = ctx.workDir.resolve("${step.name}.txt")
        val libs = ctx.listBinding("libraries").map { it.toPath() }
            .ifEmpty { ctx.stepContext?.classpath ?: emptyList() }
        output.toFile().bufferedWriter().use { w ->
            for (lib in libs) {
                w.write("-e=${lib.toAbsolutePath()}\n")
            }
        }
        return output
    }
}

/** `strip` — keep classes present in the src namespace of the
 *  extracted `mappings` data file. */
private object StripFunction : PipelineFunction {
    override val name = "mcpconfig:strip"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path {
        val input = Paths.get(ctx.vars.getValue("input"))
        val output = ctx.workDir.resolve("${step.name}Output.jar")
        val mappingPath = extractPrefix(
            ctx.descriptor, ctx.vars["mappings"], ctx.workDir
        )
        val classNames = runBlocking {
            val content = mappingPath.readText()
            val format = FormatRegistry.autodetectFormat(
                mappingPath.name, content.byteInputStream().source().buffer()
            ) ?: error("unrecognized mapping format: $mappingPath")
            val tree = format.reader.read(content)
            val src = tree.namespaces.first()
            tree.classes.mapNotNull { it.names[src] }.map { it.value }.toSet()
        }
        JarOutputStream(output.outputStream()).use { out ->
            JarFile(input.toFile()).use { inJar ->
                inJar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        val cls = entry.name.removePrefix("/")
                            .removeSuffix(".class")
                        if (cls in classNames) {
                            out.putNextEntry(java.util.jar.JarEntry("$cls.class"))
                            inJar.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
            }
        }
        return output
    }
}

/** `inject` — merge the extracted `inject` data dir into the input. */
private object InjectFunction : PipelineFunction {
    override val name = "mcpconfig:inject"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path {
        val output = ctx.workDir.resolve("${step.name}Output.jar")
        JarOutputStream(output.outputStream()).use { out ->
            JarFile(Paths.get(ctx.vars.getValue("input")).toFile())
                .use { inJar ->
                    inJar.entries().asSequence().forEach { entry ->
                        out.putNextEntry(java.util.jar.JarEntry(entry.name))
                        inJar.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            val injectDir = extractPrefix(
                ctx.descriptor, ctx.vars["inject"], ctx.workDir
            )
            for (file in injectDir.walk().filter { it.toFile().isFile }) {
                out.putNextEntry(
                    java.util.jar.JarEntry(
                        injectDir.relativize(file).toString()
                    )
                )
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return output
    }
}

/** ServiceLoader source — the mcpconfig descriptor format. */
class McpConfigFormatSource : PipelineFormatSource {
    override fun formats(): List<PipelineDescriptorFormat> =
        listOf(McpConfigDescriptorFormat())
}

/** ServiceLoader source — in-process mcpconfig functions. */
class McpConfigFunctionSource : PipelineFunctionSource {
    override fun functions(): List<PipelineFunction> = listOf(
        NoOpFunction,
        BindingFunction("mcpconfig:clientJar", "client"),
        BindingFunction("mcpconfig:serverJar", "server"),
        BindingFunction("mcpconfig:clientMappings", "clientMappings"),
        BindingFunction("mcpconfig:serverMappings", "serverMappings"),
        InputPathFunction,
        ListLibrariesFunction,
        StripFunction,
        InjectFunction,
    )
}
