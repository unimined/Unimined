package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.apache.commons.compress.archivers.jar.JarArchiveEntry
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.EmptyMappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.delegate.Delegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.io.path.*
import kotlin.time.measureTime

class MCPConfig(
    private val mcpConfig: Path,
    private var cacheDir: Path,
    private val provider: MinecraftProvider,
    private val project: Project
) {

    var useToolchains by FinalizeOnRead(true)

    private val configJson by lazy {
        mcpConfig.readZipInputStreamFor("config.json") {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }
    }

    private val specVersion by lazy {
        configJson.getAsJsonPrimitive("spec").asInt
    }

    private val isSpec6 get() = specVersion >= 6

    private val data by lazy {
        buildMap<String, String> {
            for ((key, value) in configJson.getAsJsonObject("data").entrySet()) {
                if (value.isJsonObject) {
                    put(key, value.asJsonObject.getAsJsonPrimitive(provider.side.name.lowercase()).asString)
                } else {
                    put(key, value.asString)
                }
            }
        }
    }

    private fun extractData(key: String): Path {
        val value = data[key] ?: error("Unknown data key: $key")
        mcpConfig.forEachInZip { name, stream ->
            if (name.startsWith(value)) {
                val path = cacheDir.resolve(name).createParentDirectories()
                path.outputStream().use {
                    stream.copyTo(it)
                }
            }
        }
        return cacheDir.resolve(value)
    }

    private val functions by lazy {
        buildMap<String, Function> {
            for ((name, fn) in configJson.getAsJsonObject("functions").entrySet()) {
                val o = fn.asJsonObject

                if (isSpec6) {
                    put(
                        name,
                        Function(
                            classpath = o.getAsJsonArray("classpath").map { it.asString },
                            mainClass = o.getAsJsonPrimitive("main_class")?.asString,
                            args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(),
                            jvmargs = o.getAsJsonArray("jvmargs")?.map { it.asString } ?: emptyList(),
                            javaVersion = o.getAsJsonPrimitive("java_version")?.asInt
                        )
                    )
                } else {
                    put(
                        name,
                        Function(
                            classpath = listOf(o.getAsJsonPrimitive("version").asString),
                            mainClass = null,
                            args = o.getAsJsonArray("args")?.map { it.asString } ?: emptyList(),
                            jvmargs = o.getAsJsonArray("jvmargs")?.map { it.asString } ?: emptyList(),
                            javaVersion = o.getAsJsonPrimitive("java_version")?.asInt
                        )
                    )
                }
            }
        }.toMutableMap()
    }

    fun addFunction(name: String, function: Function) {
        functions[name] = function
    }

    private val steps: MutableMap<String, ConfigStep> by lazy {
        mutableMapOf<String, ConfigStep>().apply {
            var prevStep: String? = null
            for (step in configJson.getAsJsonObject("steps").getAsJsonArray(provider.side.name.lowercase())) {
                val type = step.asJsonObject.getAsJsonPrimitive("type")?.asString ?: error("No type")
                val name = step.asJsonObject.getAsJsonPrimitive("name")?.asString ?: type

                val vars = buildMap<String, () -> String> {
                    for ((name, value) in step.asJsonObject.entrySet()) {
                        if (name != "type" && name != "name") {
                            put(name) {
                                value.asString.replace(Regex("\\{([^}]+)}")) {
                                    val varName = it.groups[1]!!.value
                                    if (varName.endsWith("Output")) {
                                        stepResult[varName.removeSuffix("Output")].output!!.absolutePathString()
                                    } else {
                                        throw IllegalArgumentException("Unknown variable: $varName")
                                    }
                                }
                            }
                        }
                    }
                }.toMutableMap()

                val step: ConfigStep = when (type) {
                    "downloadManifest", "downloadJson" -> NoOpStep(name, prevStep, vars)
                    "downloadClient" -> FileProviderStep(name, prevStep, vars) {
                        provider.minecraftData.minecraftClient.path
                    }
                    "downloadServer" -> FileProviderStep(name, prevStep, vars) {
                        provider.minecraftData.minecraftServer.path
                    }
                    "downloadClientMappings" -> FileProviderStep(name, prevStep, vars) {
                        provider.minecraftData.officialClientMappingsFile.toPath()
                    }
                    "downloadServerMappings" -> FileProviderStep(name, prevStep, vars) {
                        provider.minecraftData.officialServerMappingsFile.toPath()
                    }
                    "bundleExtractJar" -> FileProviderStep(name, prevStep, vars) {
                        Paths.get(variables.getValue("input").invoke())
                    }
                    "listLibraries" -> ListLibrariesStep(name, prevStep, vars)
                    "strip" -> {
                        vars["mappings"] = { extractData("mappings").absolutePathString() }
                        StripStep(name, prevStep, vars)
                    }
                    "inject" -> {
                        vars["inject"] = { extractData("inject").absolutePathString() }
                        InjectStep(name, prevStep, vars)
                    }
                    "patch" -> {
                        vars["patches"] = { mcpConfig.absolutePathString() }
                        vars["prefix"] = { data.getValue("patches") }
                        PatchStep(name, prevStep, vars)
                    }
                    "preProcessJar" -> FunctionStep(name, prevStep, vars, functions.getValue(type))
                    in functions.keys -> FunctionStep(name, prevStep, vars, functions.getValue(type))
                    else -> error("Unknown step type: $type")
                }

                put(name, step)

                prevStep = name
            }
        }
    }

    fun getStep(step: String): ConfigStep = steps.getValue(step)

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

    private val nullResult = StepResult("", null)

    @Suppress("RemoveExplicitTypeArguments")
    private val stepResult = defaultedMapOf<String, StepResult> {
        val step = steps.getValue(it)
        step.prev?.let { this.getValue(it) }
        project.logger.info("[Unimined/MCPConfig] Executing ${step::class.simpleName}: ${step.name}")
        val result: StepResult
        try {
            measureTime {
                result = step.execute(cacheDir.resolve(it).createDirectories())
            }.also {
                project.logger.info("[Unimined/MCPConfig] Finished step: ${step.name} in $it")
            }
        } catch (e: Exception) {
            project.logger.error("[Unimined/MCPConfig] Failed step: ${step.name}", e)
            throw e
        }
        result
    }

    fun getResultFor(step: String): StepResult {
        return stepResult.getValue(step)
    }

    interface ConfigStep {
        val name: String
        var prev: String?

        val variables: MutableMap<String, () -> String>

        fun execute(dir: Path): StepResult
    }

    inner class NoOpStep(override val name: String, override var prev: String?, override val variables: MutableMap<String, () -> String>) : ConfigStep {

        override fun execute(dir: Path): StepResult {
            return StepResult(name, null)
        }

    }

    inner class FileProviderStep(override val name: String, override var prev: String?, override val variables: MutableMap<String, () -> String>, val provider: ConfigStep.(Path) -> Path) : ConfigStep {

        override fun execute(dir: Path): StepResult {
            return StepResult(name, provider(dir))
        }

    }

    open inner class FunctionStep(
        override val name: String,
        override var prev: String?,
        override val variables: MutableMap<String, () -> String>,
        val function: Function,
    ) : ConfigStep {

        private val configuration by lazy {
            project.configurations.detachedConfiguration().apply {
                function.classpath.forEach {
                    dependencies.add(project.dependencies.create(it))
                }
            }
        }

        open fun resolveVariable(varName: String, output: Path): String =
            when (varName) {
                "output" -> output.absolutePathString()
                "log" -> output.resolveSibling("log.txt").absolutePathString()
                else -> variables[varName]?.invoke()
                    ?: extractData(varName).absolutePathString()
            }

        private fun List<String>.mapVariables(output: Path) =
            map {
                it.replace(Regex("\\{([^}]+)}")) { m ->
                    resolveVariable(m.groupValues[1], output)
                }
            }

        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("${name}Output.jar")

            if (!output.exists() || project.unimined.forceReload)
                output.deleteIfExists()

            project.execOps.javaexec {
                if (useToolchains) {
                    val toolchain = project.extensions.getByType(JavaToolchainService::class.java)
                    if (function.javaVersion != null) {
                        it.executable = try {
                            toolchain.launcherFor {
                                it.languageVersion.set(JavaLanguageVersion.of(function.javaVersion.toInt()))
                            }.get()
                        } catch (e: GradleException) {
                            throw IllegalStateException("Failed to find java version ${function.javaVersion}", e)
                        }.executablePath.asFile.absolutePath
                    } else {
                        it.executable = try {
                            toolchain.launcherFor {
                                it.languageVersion.set(JavaLanguageVersion.of(provider.minecraftData.metadata.javaVersion.majorVersion.toInt()))
                            }.get()
                        } catch (e: GradleException) {
                            throw IllegalStateException("Failed to find java version ${function.javaVersion}", e)
                        }.executablePath.asFile.absolutePath
                    }
                } else {
                    if (JavaVersion.current() < (JavaVersion.toVersion(function.javaVersion ?: 8))) {
                        error("current java version ${JavaVersion.current()} is less than required java version ${function.javaVersion} to run ${function.classpath}")
                    }
                }

                it.classpath(configuration)

                if (function.mainClass != null) {
                    // spec ≥6
                    it.mainClass.set(function.mainClass)
                } else {
                    val jar = configuration.first()
                    JarFile(jar).use { jf ->
                        it.mainClass.set(
                            jf.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS) ?: error("No Main-Class in manifest of $jar")
                        )
                    }
                }

                it.args(function.args.mapVariables(output))
                it.jvmArgs(function.jvmargs.mapVariables(output))
                project.suppressLogs(it)
            }.assertNormalExitValue().rethrowFailure()

            return StepResult(name, output)
        }
    }

    inner class StripStep(
        override val name: String,
        override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {

        private fun trimLeadingSlash(string: String): String {
            if (string.startsWith(File.separator)) {
                return string.substring(File.separator.length)
            } else if (string.startsWith("/")) {
                return string.substring(1)
            }
            return string
        }

        override fun execute(dir: Path): StepResult {
            val input = Paths.get(variables.getValue("input").invoke())
            val output = dir.resolve("${name}Output.jar")

            val mappingPath = Paths.get(variables.getValue("mappings").invoke())
            val mappings = mutableSetOf<String>()
            val mappingNs = Namespace("source")
            runBlocking {
                mappingPath.source().buffer().use {
                    val format = FormatRegistry.autodetectFormat(EnvType.JOINED, mappingPath.name, it) ?: error("Failed to detect mapping format for $mappingPath")
                    format.read(
                        StringCharReader(it.readUtf8().replace("\r", "")),
                        null,
                        EmptyMappingVisitor().delegator(object : Delegator() {

                            override fun visitClass(
                                delegate: MappingVisitor,
                                names: Map<Namespace, InternalName>
                            ): ClassVisitor? {
                                names[mappingNs]?.let {
                                    mappings.add(it.toString())
                                }
                                return null
                            }
                        }),
                        EnvType.JOINED,
                        mapOf<String, String>("obf" to "source")
                    )
                }
            }


            JarArchiveOutputStream(output.outputStream()).use { out ->
                input.forEachInZip { name, inputStream ->
                    if (!name.endsWith(".class")) return@forEachInZip
                    val className = trimLeadingSlash(name).removeSuffix(".class")
                    if (!mappings.contains(className)) return@forEachInZip
                    out.putArchiveEntry(JarArchiveEntry("$className.class"))
                    inputStream.copyTo(out)
                    out.closeArchiveEntry()
                }
            }

            return StepResult(name, output)
        }

    }

    inner class ListLibrariesStep(
        override val name: String,
        override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {

        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("$name.txt")

            output.bufferedWriter().use {
                for (lib in provider.minecraftLibraries) {
                    it.write("-e=${lib.absolutePath}\n")
                }
            }

            return StepResult(name, output)
        }
    }

    inner class InjectStep(
        override val name: String,
        override var prev: String?,
        override val variables: MutableMap<String, () -> String>
    ) : ConfigStep {

        @OptIn(ExperimentalPathApi::class)
        override fun execute(dir: Path): StepResult {
            val output = dir.resolve("${name}Output.jar")

            JarArchiveOutputStream(output.outputStream()).use { out ->
                val input = Paths.get(variables.getValue("input").invoke())
                input.forEachInZip { name, inputStream ->
                    out.putArchiveEntry(JarArchiveEntry(name))
                    inputStream.copyTo(out)
                    out.closeArchiveEntry()
                }

                val inject = Paths.get(variables.getValue("inject").invoke())
                for (file in inject.walk()) {
                    out.putArchiveEntry(JarArchiveEntry(file.relativeTo(inject).toString()))
                    file.inputStream().use {
                        it.copyTo(out)
                    }
                    out.closeArchiveEntry()
                }

            }

            return StepResult(name, output)
        }

    }

    inner class PatchStep(
        name: String,
        prev: String?,
        variables: MutableMap<String, () -> String>
    ) : FunctionStep(name, prev, variables, Function(
        listOf("net.minecraftforge:DiffPatch:2.0.12"),
        null,
        listOf(
            "-p",
            "-m",
            "FUZZY",
            "-P",
            "{prefix}",
            "-A",
            "ZIP",
            "-o",
            "{output}",
            "-r",
            "{reject}",
            "-H",
            "ZIP",
            "-s",
            "-v",
            "{input}",
            "{patches}",
        ),
        listOf(),
        javaVersion = null
    )) {

        override fun resolveVariable(varName: String, output: Path): String {
            if (varName == "reject") {
                return output.resolveSibling("${name}Reject.jar").absolutePathString()
            }
            return super.resolveVariable(varName, output)
        }

    }

    inner class UpdateCacheDir(
        override val name: String,
        override var prev: String?,
        val cacheDir: Path,
        override val variables: MutableMap<String, () -> String> = mutableMapOf(),
    ) : ConfigStep {

        override fun execute(dir: Path): StepResult {
            this@MCPConfig.cacheDir = cacheDir
            return StepResult(name, null)
        }

    }

    data class StepResult(
        val stepName: String,
        val output: Path?
    )

    data class Function(
        val classpath: List<String>,
        val mainClass: String?,
        val args: List<String>,
        val jvmargs: List<String>,
        val javaVersion: Int?
    )

}