package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.buffer
import okio.source
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.*

/**
 * mcpconfig step-graph runner — port of 1.x `MCPConfig` (FG3-6
 * `config.json` `steps`/`functions`/`data`), decoupled from Gradle:
 * tool artifacts resolve through [toolClasspath], javaexec through
 * [exec], environment artifacts (client/server jars, official
 * mappings, library classpath) through [inputs]. Everything else is a
 * pure function of the config zip + cache dir.
 *
 * Steps are memoized: [resultFor] executes a step once, then its
 * `{xOutput}` variables resolve through prior results — same contract
 * as 1.x.
 */
internal class McpConfigRunner(
    /** The resolved `mcp_config`/`neoform` zip — carries `config.json`
     *  plus the `data`-keyed files steps consume. */
    val mcpConfigZip: Path,
    /** Per-invocation cache root — step outputs land in
     *  `<cacheDir>/<stepName>/`. */
    cacheDir: Path,
    /** `client`/`server` — selects `steps.<side>` from the config. */
    side: String,
    /** Environment artifacts + tool exec hooks. */
    val env: Env
) {

    /** Injected environment artifacts — what 1.x reached into
     *  `provider.minecraftData`/`project` for. */
    class Env(
        /** `downloadClient` step input. */
        val clientJar: () -> Path,
        /** `downloadServer` step input. */
        val serverJar: () -> Path,
        /** `downloadClientMappings`/`downloadServerMappings` inputs. */
        val clientMappings: () -> Path,
        val serverMappings: () -> Path,
        /** `listLibraries` output rows (`-e=<path>`). */
        val libraries: () -> List<Path>,
        /** Resolve a tool coordinate (`version` field of a config
         *  `functions` entry) to its classpath jars. */
        val toolClasspath: (coord: String) -> List<Path>,
        /** Run a java tool — 1.x `javaexec`: manifest main class or
         *  [mainClass] arg, java version, args/jvmargs. [logSink]
         *  receives the assembled command line for diagnostics. */
        val exec: ExecPort
    )

    interface ExecPort {
        /** Execute [mainClass] (from the tool jar manifest when null)
         *  on [classpath] with mapped [args]/[jvmArgs]. [javaVersion]
         *  is the config's requested tool version, when present. */
        fun exec(
            mainClass: String?, classpath: List<Path>,
            args: List<String>, jvmArgs: List<String>,
            javaVersion: Int?
        )
    }

    var cacheDir = cacheDir
        private set

    private val json = Json { ignoreUnknownKeys = true }

    val configJson: JsonObject by lazy {
        JarFile(mcpConfigZip.toFile()).use { jar ->
            jar.getInputStream(jar.getJarEntry("config.json")).use {
                json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
            }
        }
    }

    /** `data` entries — side-aware (`{client, server}` objects pick
     *  the side's value). */
    private val data: Map<String, String> by lazy {
        configJson["data"]!!.jsonObject.mapValues { (_, v) ->
            (v as? JsonObject)?.get(side)?.jsonPrimitive?.content
                ?: v.jsonPrimitive.content
        }
    }

    /** Extract every zip entry under `data[key]`'s prefix to
     *  [cacheDir]; returns the extracted root. */
    fun extractData(key: String): Path {
        val prefix = data[key] ?: error("Unknown data key: $key")
        JarFile(mcpConfigZip.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .forEach { entry ->
                    val target = cacheDir.resolve(entry.name)
                    target.parent.createDirectories()
                    jar.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
        }
        return cacheDir.resolve(prefix)
    }

    data class Function(
        val version: String,
        val javaVersion: String?,
        val args: List<String>,
        val jvmArgs: List<String>
    )

    val functions: Map<String, Function> by lazy {
        configJson["functions"]!!.jsonObject.mapValues { (_, f) ->
            val obj = f.jsonObject
            Function(
                obj["version"]!!.jsonPrimitive.content,
                obj["java_version"]?.jsonPrimitive?.content,
                xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig.flattenArgs(obj["args"]),
                xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig.flattenArgs(obj["jvmargs"])
            )
        }
    }

    // ---- step graph -------------------------------------------------

    interface ConfigStep {
        val name: String
        var prev: String?
        val variables: MutableMap<String, () -> String>
        fun execute(dir: Path): StepResult
    }

    data class StepResult(val stepName: String, val output: Path?)

    private val steps: MutableMap<String, ConfigStep> by lazy {
        mutableMapOf<String, ConfigStep>().also { built ->
            var prev: String? = null
            val stepsJson = configJson["steps"]!!.jsonObject[side]
                ?: error("mcpconfig has no steps for side '$side'")
            for (stepJson in stepsJson.jsonArray) {
                val obj = stepJson.jsonObject
                val type = obj["type"]!!.jsonPrimitive.content
                val name = obj["name"]?.jsonPrimitive?.content ?: type
                val vars = obj.entries.filter { it.key != "type" && it.key != "name" }
                    .associate { (k, v) ->
                        // step-var values may reference prior step
                        // outputs via {xOutput} — resolved at invoke
                        // time (1.x parity: other {x} forms error)
                        k to ({
                            v.jsonPrimitive.content.replace(Regex("\\{([^}]+)}")) {
                                val varName = it.groupValues[1]
                                if (varName.endsWith("Output"))
                                    resultFor(varName.removeSuffix("Output"))
                                        .output!!.absolutePathString()
                                else throw IllegalArgumentException(
                                    "Unknown variable: $varName"
                                )
                            }
                        })
                    }.toMutableMap()

                val step: ConfigStep = when (type) {
                    "downloadManifest", "downloadJson" -> NoOpStep(name, prev, vars)
                    "downloadClient" -> FileProviderStep(name, prev, vars) { env.clientJar() }
                    "downloadServer" -> FileProviderStep(name, prev, vars) { env.serverJar() }
                    "downloadClientMappings" -> FileProviderStep(name, prev, vars) { env.clientMappings() }
                    "downloadServerMappings" -> FileProviderStep(name, prev, vars) { env.serverMappings() }
                    "bundleExtractJar" -> FileProviderStep(name, prev, vars) {
                        Paths.get(variables.getValue("input").invoke())
                    }

                    "listLibraries" -> ListLibrariesStep(name, prev, vars)
                    "strip" -> {
                        vars["mappings"] = { extractData("mappings").absolutePathString() }
                        StripStep(name, prev, vars)
                    }

                    "inject" -> {
                        vars["inject"] = { extractData("inject").absolutePathString() }
                        InjectStep(name, prev, vars)
                    }

                    "patch" -> {
                        vars["patches"] = { mcpConfigZip.absolutePathString() }
                        vars["prefix"] = { data.getValue("patches") }
                        PatchStep(name, prev, vars)
                    }

                    in functions.keys -> FunctionStep(name, prev, vars, functions.getValue(type))
                    else -> error("Unknown step type: $type")
                }
                built[name] = step
                prev = name
            }
        }
    }

    /** Insert [step] directly before [nextStep] — 1.x `insertBefore`
     *  (mcpconfig `forgeInject`/`applyAts` splice-ins). */
    fun insertBefore(nextStep: String, step: ConfigStep, updateArgs: Map<String, () -> String>) {
        val next = steps.getValue(nextStep)
        steps[step.name] = step
        step.prev = next.prev
        next.prev = step.name
        step.variables.putAll(next.variables)
        next.variables.putAll(updateArgs)
    }

    fun addStep(step: ConfigStep) {
        steps[step.name] = step
    }

    private val stepResults = mutableMapOf<String, StepResult>()

    /** Memoized step execution — running a step runs its predecessor
     *  chain first. */
    fun resultFor(name: String): StepResult = stepResults.getOrPut(name) {
        val step = steps.getValue(name)
        step.prev?.let { resultFor(it) }
        step.execute(cacheDir.resolve(name).createDirectories())
    }

    /** Variable resolution for `{x}`-style templating in step args:
     *  `{xOutput}` → prior step output, `{output}`/`{log}` → this
     *  step's output, else `variables`/`data`. */
    private fun resolveVariable(varName: String, output: Path?, vars: Map<String, () -> String>): String =
        when {
            varName == "output" -> output!!.absolutePathString()
            varName == "log" -> output!!.resolveSibling("log.txt").absolutePathString()
            varName.endsWith("Output") ->
                resultFor(varName.removeSuffix("Output")).output!!.absolutePathString()

            vars.containsKey(varName) -> vars.getValue(varName).invoke()
            else -> extractData(varName).absolutePathString()
        }

    private fun List<String>.mapVariables(output: Path?, vars: Map<String, () -> String>) =
        map { arg ->
            arg.replace(Regex("\\{([^}]+)}")) {
                resolveVariable(it.groupValues[1], output, vars)
            }
        }

    // ---- step impls --------------------------------------------------

    class NoOpStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {
        override fun execute(dir: Path) = StepResult(name, null)
    }

    class FileProviderStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>,
        val provider: ConfigStep.(Path) -> Path
    ) : ConfigStep {
        override fun execute(dir: Path) = StepResult(name, provider(dir))
    }

    inner class ListLibrariesStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {
        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("$name.txt")
            output.toFile().bufferedWriter().use { w ->
                for (lib in env.libraries()) w.write("-e=${lib.toAbsolutePath()}\n")
            }
            return StepResult(name, output)
        }
    }

    /**
     * `strip` — keep only classes present in the src namespace of the
     * `mappings` data file (1.x reads the tsrg/mcp mappings from the
     * config zip).
     */
    class StripStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {
        override fun execute(dir: Path): StepResult {
            val input = Paths.get(variables.getValue("input").invoke())
            val output = dir.resolve("${name}Output.jar")

            val mappingPath = Paths.get(variables.getValue("mappings").invoke())
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
                            val cls = entry.name.removePrefix("/").removeSuffix(".class")
                            if (cls in classNames) {
                                out.putNextEntry(java.util.jar.JarEntry("$cls.class"))
                                inJar.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                }
            }
            return StepResult(name, output)
        }
    }

    /** `inject` — merge a directory's contents into the input jar. */
    class InjectStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {
        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("${name}Output.jar")
            JarOutputStream(output.outputStream()).use { out ->
                JarFile(Paths.get(variables.getValue("input").invoke()).toFile()).use { inJar ->
                    inJar.entries().asSequence().forEach { entry ->
                        out.putNextEntry(java.util.jar.JarEntry(entry.name))
                        inJar.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                val injectDir = Paths.get(variables.getValue("inject").invoke())
                for (file in injectDir.walk().filter { it.toFile().isFile }) {
                    out.putNextEntry(
                        java.util.jar.JarEntry(injectDir.relativize(file).toString())
                    )
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            return StepResult(name, output)
        }
    }

    /** `functions.<type>` entries — resolved tool jar + javaexec
     *  with `{var}`-templated args. */
    open inner class FunctionStep(
        override val name: String, override var prev: String?,
        override val variables: MutableMap<String, () -> String>,
        val function: Function
    ) : ConfigStep {
        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("${name}Output.jar")
            if (!output.exists()) {
                val classpath = env.toolClasspath(function.version)
                val mainClass = classpath.firstNotNullOfOrNull { jar ->
                    runCatching {
                        JarFile(jar.toFile()).use {
                            it.manifest.mainAttributes
                                .getValue(java.util.jar.Attributes.Name.MAIN_CLASS)
                        }
                    }.getOrNull()
                } ?: error("could not determine main class for ${function.version}")
                env.exec.exec(
                    mainClass, classpath,
                    function.args.mapVariables(output, variables),
                    function.jvmArgs.mapVariables(output, variables),
                    function.javaVersion?.toIntOrNull()
                )
            }
            return StepResult(name, output)
        }
    }

    /** `patch` — DiffPatch tool over `patches/` (1.x fixed
     *  `net.minecraftforge:DiffPatch:2.0.12` invocation). */
    inner class PatchStep(
        name: String, prev: String?,
        variables: MutableMap<String, () -> String>
    ) : FunctionStep(
        name, prev, variables, Function(
            "net.minecraftforge:DiffPatch:2.0.12",
            null,
            listOf(
                "-p", "-m", "FUZZY", "-P", "{prefix}", "-A", "ZIP",
                "-o", "{output}", "-r", "{reject}", "-H", "ZIP", "-s",
                "-v", "{input}", "{patches}"
            ),
            emptyList()
        )
    ) {
        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("${name}Output.jar")
            variables["reject"] = {
                output.resolveSibling("${name}Reject.jar").absolutePathString()
            }
            return super.execute(dir)
        }
    }

    /** Swap the cache dir mid-chain (1.x `UpdateCacheDir` — the
     *  per-run mcpconfig cache). */
    inner class UpdateCacheDirStep(
        override val name: String,
        override var prev: String?,
        private val newDir: Path,
        override val variables: MutableMap<String, () -> String> = mutableMapOf()
    ) : ConfigStep {
        override fun execute(dir: Path): StepResult {
            cacheDir = newDir
            return StepResult(name, null)
        }
    }
}
