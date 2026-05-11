package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import com.google.common.base.Stopwatch
import com.google.common.hash.Hashing
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.util.getFiles
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

data class McpExecutor(
    private val project: Project,
    private val provider: MinecraftProvider,
    private val cache: Path,
    private val steps: List<McpConfigStep>,
    private val functions: Map<String, McpConfigFunction>,
    private val extraConfig: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        private val STEP_LOG_LEVEL = LogLevel.LIFECYCLE

        // Some of these files linked to the old Forge maven, let's follow the redirects to the new one.
        @Throws(IOException::class)
        private fun redirectAwareDownload(urlString: String, path: Path) {
            var url = URL(urlString)
            if (url.protocol == "http") {
                url = URL("https", url.host, url.port, url.file)
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM || connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                redirectAwareDownload(connection.getHeaderField("Location"), path)
            } else {
                connection.inputStream.use { `in` -> Files.copy(`in`, path, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    @Throws(IOException::class)
    private fun getDownloadCache(): Path {
        val downloadCache = cache.resolve("downloads")
        Files.createDirectories(downloadCache)
        return downloadCache
    }

    private fun getStepCache(step: String): Path {
        return cache.resolve(step)
    }

    @Throws(IOException::class)
    private fun createStepCache(step: String): Path {
        val stepCache = getStepCache(step)
        stepCache.createDirectories()
        return stepCache
    }

    private fun resolve(step: McpConfigStep, value: ConfigValue): String {
        return value.fold(ConfigValue.Constant::value) { variable ->
            val name: String = variable.name
            val valueFromStep: ConfigValue? = step.config[name]

            // If the variable isn't defined in the step's config map, skip it.
            // Also skip if it would recurse with the same variable.
            if (valueFromStep != null && !valueFromStep.equals(variable)) {
                // Otherwise, resolve the nested variable.
                return@fold resolve(step, valueFromStep)
            }
            if (name == ConfigValue.SRG_MAPPINGS_NAME) {
                return@fold mappings(step).toAbsolutePath().toString()
            } else if (extraConfig.containsKey(name)) {
                return@fold extraConfig[name]!!
            }
            throw IllegalArgumentException("Unknown MCP config variable: $name")
        }
    }

    private fun mappings(step: McpConfigStep): Path {
        val transformer = ((provider.mcPatcher as ForgeLikeMinecraftTransformer).forgeTransformer as FG3MinecraftTransformer)
        val configuration = project.configurations.detachedConfiguration()
        configuration.dependencies.add(transformer.mcpConfig)
        configuration.resolve()
        val mcpConfig = configuration.getFiles(transformer.mcpConfig, "zip").singleFile
        val target = getStepCache(step.name).createDirectories()
            .resolve(transformer.mcpConfigData.mappingsPath.split("/").last())
        mcpConfig.toPath().readZipInputStreamFor(transformer.mcpConfigData.mappingsPath) {
            target.writeBytes(it.readBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
        }
        return target.toAbsolutePath()
    }

    @Throws(IOException::class)
    fun executeUpTo(step: String): Path {
        extraConfig.clear()

        // Find the total number of steps we need to execute.
        val totalSteps: Int = steps.firstOrNull { s -> s.name == step }
            ?.let { s -> steps.indexOf(s) + 1 } ?: steps.size
        var currentStepIndex = 0
        project.logger.log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps)
        for (currentStep in steps) {
            currentStepIndex++
            val stepLogic: StepLogic = getStepLogic(currentStep.type)
            project.logger.log(
                STEP_LOG_LEVEL,
                ":step {}/{} - {}",
                currentStepIndex,
                totalSteps,
                stepLogic.getDisplayName(currentStep.name)
            )
            val stopwatch = Stopwatch.createStarted()
            stepLogic.execute(
                ExecutionContextImpl(
                    currentStep
                )
            )
            project.logger.log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name, stopwatch.stop())
            if (currentStep.name.equals(step)) {
                break
            }
        }
        return Paths.get(extraConfig[ConfigValue.OUTPUT])
    }

    private fun getStepLogic(type: String): StepLogic {
        return when (type) {
            "downloadManifest", "downloadJson" -> StepLogic.NoOp()
            "downloadClient" -> StepLogic.NoOpWithFile { provider.minecraftData.minecraftClient.path }
            "downloadServer" -> StepLogic.NoOpWithFile { provider.minecraftData.minecraftServer.path }
            "strip" -> StepLogic.Strip()
//            "strip" -> StepLogic.NoOp()
            "listLibraries" -> StepLogic.ListLibraries()
            "downloadClientMappings" -> StepLogic.DownloadManifestFile(
                provider.minecraftData.officialClientMappingsFile.toPath()
            )

            "downloadServerMappings" -> StepLogic.DownloadManifestFile(
                provider.minecraftData.officialServerMappingsFile.toPath()
            )
            "bundleExtractJar" -> StepLogic.NoOpWithFile {
                Paths.get(it.resolve(ConfigValue.Variable("input")))
            }

            else -> {
                if (functions.containsKey(type)) {
                    StepLogic.OfFunction(functions[type]!!)
                } else {
                    throw UnsupportedOperationException("MCP config step type: $type, available: ${functions.keys}")
                }
            }
        }
    }

    private inner class ExecutionContextImpl constructor(private val step: McpConfigStep): StepLogic.ExecutionContext {
        override fun logger(): Logger {
            return project.logger
        }

        @Throws(IOException::class)
        override fun setOutput(fileName: String): Path {
            createStepCache(step.name)
            return setOutput(getStepCache(step.name).resolve(fileName))
        }

        override fun setOutput(output: Path): Path {
            val absolutePath = output.toAbsolutePath().toString()
            extraConfig.put(ConfigValue.OUTPUT, absolutePath)
            extraConfig.put(step.name + ConfigValue.PREVIOUS_OUTPUT_SUFFIX, absolutePath)
            return output
        }

        override fun mappings(): Path {
            val transformer = ((provider.mcPatcher as ForgeLikeMinecraftTransformer).forgeTransformer as FG3MinecraftTransformer)
            val configuration = project.configurations.detachedConfiguration()
            configuration.dependencies.add(transformer.mcpConfig)
            configuration.resolve()
            val mcpConfig = configuration.getFiles(transformer.mcpConfig, "zip").singleFile
            val target = getStepCache(step.name).createDirectories()
                .resolve(transformer.mcpConfigData.mappingsPath.split("/").last())
            mcpConfig.toPath().readZipInputStreamFor(transformer.mcpConfigData.mappingsPath) {
                target.writeBytes(it.readBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
            }
            return target.toAbsolutePath()
        }

        override fun resolve(value: ConfigValue): String {
            return this@McpExecutor.resolve(step, value)
        }

        @Throws(IOException::class)
        override fun download(url: String): Path {
            val path: Path = getDownloadCache().resolve(
                Hashing.sha256()
                    .hashString(url, StandardCharsets.UTF_8)
                    .toString()
                    .substring(0, 24)
            )
            redirectAwareDownload(url, path)
            return path
        }

        override fun javaexec(configurator: Action<in JavaExecSpec>) {
            SubprocessExecutor.exec(project, configurator).rethrowFailure().assertNormalExitValue()
        }

        override fun resolveCoordinates(coords: List<String>): List<Path> {
            val config = project.configurations.detachedConfiguration()
            for (coord in coords) {
                config.dependencies.add(project.dependencies.create(coord))
            }
            return config.resolve().map { it.toPath() }
        }

        override val minecraftLibraries: Set<File>
            get() = provider.minecraftLibraries.resolve()
    }
}