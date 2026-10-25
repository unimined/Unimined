package xyz.wagyourtail.unimined.runs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.mclauncher.Assets
import xyz.wagyourtail.unimined.mclauncher.LaunchEnvironment
import xyz.wagyourtail.unimined.mclauncher.LaunchResolver
import xyz.wagyourtail.unimined.mclauncher.Natives
import xyz.wagyourtail.unimined.mclauncher.OfflineAuth
import xyz.wagyourtail.unimined.mclauncher.OperatingSystem
import xyz.wagyourtail.unimined.mclauncher.Session
import xyz.wagyourtail.unimined.mclauncher.VersionChain
import xyz.wagyourtail.unimined.mclauncher.VersionData
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import javax.inject.Inject

/**
 * Executes a Minecraft launch: resolves the version chain, extracts
 * natives, prepares assets, builds the launch plan via `mc-launcher`,
 * and forks the JVM. All metadata work happens at execution time so the
 * task is configuration-cache safe.
 */
@UntrackedTask(because = "runs the game — a side-effecting process fork, not a build output")
abstract class MinecraftRunTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    /** Launch metadata — serializable reconstruction data from the
     *  provider; the version chain is fetched lazily inside it. */
    @get:Internal
    abstract val launchMetadata: Property<LaunchMetadata>

    /** Ordered launch classpath (dev artifact + libraries + mods). */
    @get:InputFiles
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** Native jars resolved for this run's OS. */
    @get:InputFiles
    @get:Classpath
    abstract val nativesClasspath: ConfigurableFileCollection

    /** Asset index document for this environment. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val assetIndexFile: RegularFileProperty

    /** Asset index id exposed as `assets_index_name`. */
    @get:Input
    @get:Optional
    abstract val assetIndexId: Property<String>

    /** Working directory for the launched game. */
    @get:Internal
    abstract val runDirectory: DirectoryProperty

    /** Where natives are extracted for this run. */
    @get:Internal
    abstract val nativesDirectory: DirectoryProperty

    /** Asset object root (`objects/<hh>/<hash>` layout). */
    @get:Internal
    abstract val assetsDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val mainClass: Property<String>

    @get:Input
    abstract val side: Property<RunSide>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val programArgs: ListProperty<String>

    /** Extra args contributed by transform run mutators. */
    @get:Input
    abstract val mutatedJvmArgs: ListProperty<String>

    @get:Input
    abstract val mutatedProgramArgs: ListProperty<String>

    @get:Input
    abstract val mutatedEnvironment: MapProperty<String, String>

    @get:Input
    abstract val environmentVariables: MapProperty<String, String>

    @get:Input
    abstract val features: SetProperty<String>

    @get:Input
    abstract val username: Property<String>

    @get:Input
    @get:Optional
    abstract val width: Property<Int>

    @get:Input
    @get:Optional
    abstract val height: Property<Int>

    @get:Input
    abstract val downloadAssets: Property<Boolean>

    @get:Input
    abstract val offline: Property<Boolean>

    init {
        jvmArgs.convention(emptyList())
        programArgs.convention(emptyList())
        mutatedJvmArgs.convention(emptyList())
        mutatedProgramArgs.convention(emptyList())
        mutatedEnvironment.convention(emptyMap())
        environmentVariables.convention(emptyMap())
        features.convention(emptySet())
        username.convention("Dev")
        downloadAssets.convention(true)
        offline.convention(false)
    }

    @TaskAction
    fun launch() {
        val metadata = launchMetadata.get()
        val document = VersionData.parse(
            VersionChain.resolve(metadata.versionJson(metadata.rootId), metadata::versionJson)
        )
        val os = OperatingSystem.current()

        val nativesDir = nativesDirectory.get().asFile.toPath()
        if (nativesClasspath.files.isNotEmpty()) {
            Natives.extract(nativesClasspath.files.map { it.toPath() }, nativesDir, document, os)
        }

        val assetsRoot = assetsDirectory.get().asFile.toPath()
        if (downloadAssets.get() && assetIndexFile.isPresent) {
            val indexJson = assetIndexFile.get().asFile.readText()
            val downloaded = Assets.downloadObjects(indexJson, assetsRoot, offline.get())
            if (downloaded > 0) logger.lifecycle("Unimined: fetched $downloaded asset objects")
        }

        val plan = LaunchResolver.resolve(
            document,
            LaunchEnvironment(
                classpath = runtimeClasspath.files.map { it.toPath() },
                gameDir = runDirectory.get().asFile.toPath(),
                assetsDir = assetsRoot,
                assetIndexId = assetIndexId.orNull,
                nativesDir = nativesDir,
                session = session(),
                features = features.get(),
                resolutionWidth = width.orNull,
                resolutionHeight = height.orNull,
                launcherVersion = project.version.toString().ifBlank { "2.0.0" }
            )
        )

        val resolvedMainClass = mainClass.orNull ?: defaultMainClass(document, plan.mainClass)

        runDirectory.get().asFile.mkdirs()
        execOperations.javaexec { exec ->
            exec.workingDir = runDirectory.get().asFile
            exec.mainClass.set(resolvedMainClass)
            exec.classpath = runtimeClasspath
            exec.jvmArgs(
                plan.jvmArgs +
                    "-Djava.library.path=$nativesDir" +
                    jvmArgs.get() +
                    mutatedJvmArgs.get()
            )
            exec.args(plan.programArgs + programArgs.get() + mutatedProgramArgs.get())
            exec.environment(plan.environment + mutatedEnvironment.get() + environmentVariables.get())
        }
    }

    private fun session(): Session = OfflineAuth(username.get()).session()

    /** Server runs get no `mainClass` from the version document — fall
     *  back through the era's server entrypoints. */
    private fun defaultMainClass(document: VersionData, documentMain: String): String {
        if (side.get() == RunSide.CLIENT) return documentMain
        return if ((document.javaMajorVersion ?: 8) >= 17) {
            "net.minecraft.server.Main"
        } else {
            "net.minecraft.server.MinecraftServer"
        }
    }

}
