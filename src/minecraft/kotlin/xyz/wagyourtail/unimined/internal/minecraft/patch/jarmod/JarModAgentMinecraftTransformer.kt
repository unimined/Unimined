package xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModAgentPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.task.RemapJarTaskImpl
import xyz.wagyourtail.unimined.util.getTempFilePath
import xyz.wagyourtail.unimined.util.suppressLogs
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*


open class JarModAgentMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    jarModProvider: String = "jarMod",
    providerName: String = "JarModAgent"
) : JarModMinecraftTransformer(
    project, provider, jarModProvider, providerName
), JarModAgentPatcher {
    companion object {
        private const val JMA_TRANSFORMERS = "jma.transformers"
        private const val JMA_PRIORITY_CLASSPATH = "jma.priorityClasspath"
        private const val JMA_DEBUG = "jma.debug"
    }

    init {
        provider.mods.modImplementation {
            mixinRemap {
                enableJarModAgent()
            }
        }
    }

    @Deprecated("may violate mojang's EULA... use at your own risk. this is not recommended and is only here for legacy reasons and testing.")
    override var compiletimeTransforms: Boolean = false

    override var jarModAgent = project.configurations.maybeCreate("jarModAgent".withSourceSet(provider.sourceSet)).also {
        provider.minecraftLibraries.extendsFrom(it)
    }

    val jmaFile by lazy {
        jarModAgent.resolve().first { it.extension == "jar" }.toPath()
    }

    private val transforms = mutableListOf<String>()

    override fun transforms(transform: String) {
        this.transforms.add(transform)
    }

    override fun transforms(transforms: List<String>) {
        this.transforms.addAll(transforms)
    }

    override fun agentVersion(vers: String) {
        project.unimined.wagYourMaven("releases")
        project.unimined.wagYourMaven("snapshots")
        jarModAgent.dependencies.add(
            project.dependencies.create(
                "xyz.wagyourtail.unimined:jarmod-agent:$vers:all"
            ).also {
                (it as ExternalDependency).isTransitive = false
            }
        )
    }

    override fun apply() {
        if (jarModAgent.dependencies.isEmpty()) {
            agentVersion("0.1.4-SNAPSHOT")
        }

        super.apply()
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        applyJarModAgent(config)
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        applyJarModAgent(config)
    }

    private fun applyJarModAgent(config: RunConfig) {
        if (transforms.isNotEmpty()) {
            config.jvmArgs("-D${JMA_TRANSFORMERS}=${transforms.joinToString(File.pathSeparator)}")
        }
        // priority classpath
        val priorityClasspath = provider.detectCombineWithSourceSets().map { it.second.output.classesDirs.toMutableSet().also {set-> it.second.output.resourcesDir.let { set.add(it) } } }.flatten()
        if (priorityClasspath.isNotEmpty()) {
            config.jvmArgs("-D${JMA_PRIORITY_CLASSPATH}=${priorityClasspath.joinToString(File.pathSeparator) { it.absolutePath }}")
        }
        config.jvmArgs("-javaagent:${jmaFile}")
        //TODO: add mods to priority classpath, and resolve their jma.transformers
    }

    override fun beforeRemapJarTask(remapJarTask: AbstractRemapJarTask, input: Path): Path {
        remapJarTask.mixinRemap {
            enableJarModAgent()
        }
        @Suppress("DEPRECATION")
        return if (compiletimeTransforms && transforms.isNotEmpty()) {
            project.logger.lifecycle("[Unimined/JarModAgentTransformer] Running compile time transforms for ${remapJarTask.name}...")
            val output = getTempFilePath("${input.nameWithoutExtension}-jma", ".jar")
            Files.copy(input, output)
            try {
                val classpath = (remapJarTask as RemapJarTaskImpl).provider.sourceSet.runtimeClasspath.files.toMutableSet()

                val result = project.javaexec {
                    it.jvmArgs = listOf(
                        "-D${JMA_TRANSFORMERS}=${transforms.joinToString(File.pathSeparator)}",
                        "-D${JMA_DEBUG}=true")
                    it.args = listOf(
                        input.absolutePathString(),
                        classpath.joinToString(File.pathSeparator) { it.absolutePath },
                        output.absolutePathString(),
                    )
                    it.mainClass.set("xyz.wagyourtail.unimined.jarmodagent.JarModAgent")
                    it.classpath = jarModAgent

                    project.suppressLogs(it)
                }.assertNormalExitValue().rethrowFailure()
            } catch (e: Exception) {
                project.logger.error("[Unimined/JarModAgentTransformer] Failed to transform $input")
                output.deleteIfExists()
                throw e
            }
            output
        } else {
            super.beforeRemapJarTask(remapJarTask, input)
        }
    }

}