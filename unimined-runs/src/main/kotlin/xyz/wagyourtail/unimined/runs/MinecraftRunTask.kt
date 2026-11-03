package xyz.wagyourtail.unimined.runs

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import xyz.wagyourtail.commonskt.utils.resolveArgs
import xyz.wagyourtail.unimined.mclauncher.*
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

    private companion object {
        val LAUNCH_TOKEN = Regex("\\$\\{([^}]+)}")
    }

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

    /** Data piped to the launched process's stdin — [RunConfig.stdin]. */
    @get:Input
    @get:Optional
    abstract val stdin: Property<String>

    @get:Input
    abstract val features: SetProperty<String>

    /** `version` of the launching plugin build — substituted into the
     *  version manifest's launch metadata; a property since
     *  `task.project` can't be touched at execution time (config
     *  cache). */
    @get:Input
    abstract val launcherVersion: Property<String>

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

    /**
     * `${key}` substitution values applied to args/jvmArgs/env at
     * launch — the environment-level defaults (`assets_root`,
     * `game_dir`, `natives_root`, ...). Mutator-contributed keys
     * arrive via [mutatedLaunchProperties].
     */
    @get:Input
    abstract val launchProperties: MapProperty<String, String>

    /** `${key}` substitutions contributed by transform run mutators. */
    @get:Input
    abstract val mutatedLaunchProperties: MapProperty<String, String>

    /**
     * Print the fully-resolved launch (main class, jvm/program args,
     * env, classpath) instead of exec'ing it — verification/debug
     * surface; the run wiring itself (mutators, natives, assets) is
     * still evaluated.
     */
    @get:Input
    abstract val dryRun: Property<Boolean>

    /** Java executable for the fork — the env's toolchain-resolved
     *  launcher path, or the build JVM's `java` when toolchains are
     *  disabled (1.x `RunConfig.javaVersion`/`javaLauncher` parity). */
    @get:Input
    @get:Optional
    abstract val javaExecutable: Property<String>

    /** The environment's effective java requirement — enforced
     *  against the build JVM when [useToolchains] is off. */
    @get:Input
    @get:Optional
    abstract val requiredJavaVersion: Property<Int>

    /** Whether launchers resolved through Gradle java toolchains —
     *  `environment.useToolchains` at wiring time. */
    @get:Input
    abstract val useToolchains: Property<Boolean>

    init {
        jvmArgs.convention(emptyList())
        programArgs.convention(emptyList())
        mutatedJvmArgs.convention(emptyList())
        mutatedProgramArgs.convention(emptyList())
        mutatedEnvironment.convention(emptyMap())
        environmentVariables.convention(emptyMap())
        launchProperties.convention(emptyMap())
        mutatedLaunchProperties.convention(emptyMap())
        features.convention(emptySet())
        username.convention("Dev")
        downloadAssets.convention(true)
        offline.convention(false)
        dryRun.convention(false)
        useToolchains.convention(true)
    }

    @TaskAction
    fun launch() {
        val requiredJava = requiredJavaVersion.orNull
        if (!useToolchains.get() && requiredJava != null &&
            org.gradle.api.JavaVersion.current() <
                org.gradle.api.JavaVersion.toVersion(requiredJava)) {
            error("unimined: '$path' requires Java $requiredJava but " +
                "toolchains are disabled and the build JVM is " +
                org.gradle.api.JavaVersion.current())
        }
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
            // pre-1.6 games load sounds/music from <gamedir>/resources;
            // map_to_resources indexes must be materialized there before
            // launch or the in-game downloader hits the dead S3 bucket
            if (side.get() != RunSide.SERVER && Assets.mapsToResources(indexJson)) {
                val resourcesDir = runDirectory.get().asFile.toPath().resolve("resources")
                val copied = Assets.copyToResources(indexJson, assetsRoot, resourcesDir)
                if (copied > 0) logger.lifecycle("Unimined: mapped $copied assets into resources/")
            }
        }

        // monolithic legacy ASM (launchwrapper-era `asm-all` /
        // `asm-debug-all`) conflicts with the modular `asm` artifact
        // on loader-managed classpaths (fabric knot rejects duplicate
        // ASM packages); drop it only when a modern `asm-<v>.jar` is
        // present so launchwrapper-only envs keep their ASM
        val runtimeFiles = runtimeClasspath.files.toList()
        val dedupedClasspath = if (runtimeFiles.any { it.name.matches(Regex("""asm-\d.*\.jar""")) })
            runtimeFiles.filterNot {
                it.name.startsWith("asm-all-") || it.name.startsWith("asm-debug-all-")
            }
        else runtimeFiles

        val plan = LaunchResolver.resolve(
            document,
            LaunchEnvironment(
                classpath = dedupedClasspath.map { it.toPath() },
                gameDir = runDirectory.get().asFile.toPath(),
                assetsDir = assetsRoot,
                assetIndexId = assetIndexId.orNull,
                nativesDir = nativesDir,
                session = session(),
                features = features.get(),
                resolutionWidth = width.orNull,
                resolutionHeight = height.orNull,
                launcherVersion = launcherVersion.get(),
                extraJvmArgs = betacraftProxyArgs(document.id)
            )
        )

        val resolvedMainClass = mainClass.orNull ?: defaultMainClass(document, plan.mainClass)

        val subst = launchProperties.get() + mutatedLaunchProperties.get()

        if (dryRun.get()) {
            val allJvmArgs = subst.resolveLaunchArgs(
                plan.jvmArgs + "-Djava.library.path=$nativesDir" +
                        jvmArgs.get() + mutatedJvmArgs.get()
            )
            val allArgs = subst.resolveLaunchArgs(
                (if (side.get() == RunSide.SERVER) emptyList() else plan.programArgs) +
                        programArgs.get() + mutatedProgramArgs.get()
            )
            val allEnv = plan.environment +
                    subst.resolveLaunchArgs(
                        mutatedEnvironment.get() + environmentVariables.get()
                    )
            println("[unimined:dry-run] $path")
            println("[unimined:dry-run] mainClass=$resolvedMainClass")
            println("[unimined:dry-run] jvmArgs=${allJvmArgs.joinToString(" ")}")
            println("[unimined:dry-run] programArgs=${allArgs.joinToString(" ")}")
            allEnv.forEach { (k, v) ->
                println("[unimined:dry-run] env.$k=$v")
            }
            println("[unimined:dry-run] classpathEntries=${runtimeClasspath.files.size}")
            javaExecutable.orNull?.let {
                println("[unimined:dry-run] javaExecutable=$it")
            }
            return
        }

        runDirectory.get().asFile.mkdirs()
        execOperations.javaexec { exec ->
            exec.workingDir = runDirectory.get().asFile
            javaExecutable.orNull?.let { exec.executable = it }
            exec.mainClass.set(resolvedMainClass)
            exec.classpath = project.files(dedupedClasspath)
            exec.jvmArgs(
                subst.resolveLaunchArgs(
                    plan.jvmArgs +
                            "-Djava.library.path=$nativesDir" +
                            jvmArgs.get() +
                            mutatedJvmArgs.get()
                )
            )
            // the version document's `game` args are client-only
            // (--username/--accessToken/...); servers take user args only
            exec.args(
                subst.resolveLaunchArgs(
                    (if (side.get() == RunSide.SERVER) emptyList() else plan.programArgs) +
                            programArgs.get() + mutatedProgramArgs.get()
                )
            )
            exec.environment(
                plan.environment + subst.resolveLaunchArgs(
                    mutatedEnvironment.get() + environmentVariables.get()
                )
            )
            stdin.orNull?.let {
                // ensure the final command is newline-terminated or the
                // console reader never dispatches it
                exec.standardInput = java.io.ByteArrayInputStream(
                    (if (it.endsWith("\n")) it else "$it\n").toByteArray()
                )
            }
        }
    }

    /**
     * `${key}` substitution — 1.x `RunConfig.applyAll` parity: unknown
     * keys warn and substitute `""` (the launch token might belong to
     * an era/config the loader doesn't model; hard-failing masks the
     * run).
     */
    private fun Map<String, String>.resolveLaunchArgs(args: List<String>): List<String> {
        val missing = args.flatMap { arg ->
            LAUNCH_TOKEN.findAll(arg).map { it.groupValues[1] }
        }.distinct().filter { it !in this }
        missing.forEach {
            logger.warn("[unimined] launch property '\${$it}' unset — substituting \"\"")
        }
        return (this + missing.associateWith { "" }).resolveArgs(args)
    }

    private fun Map<String, String>.resolveLaunchArgs(
        values: Map<String, String>
    ): Map<String, String> =
        values.mapValues { (_, v) -> resolveLaunchArgs(listOf(v)).single() }

    private fun session(): Session = OfflineAuth(username.get()).session()

    /**
     * Beta/alpha-era games fetched their resources through betacraft's
     * proxy — its `.info` file carries `proxy-args` that must reach the
     * JVM args (1.x parity). Pre-1.6 ids (`a`/`b`/`c` prefix) only;
     * fail-soft — missing/invalid `.info` just means no proxy args.
     */
    private fun betacraftProxyArgs(versionId: String?): List<String> {
        if (versionId.isNullOrEmpty() || versionId[0] !in "abc") return emptyList()
        return try {
            val info = runDirectory.get().asFile
                .resolve(".unimined/betacraft.info")
            if (!info.exists() && !offline.get()) {
                info.parentFile.mkdirs()
                java.net.URI(
                    "https://files.betacraft.uk/launcher/assets/jsons/$versionId.info"
                ).toURL().openStream().use { info.writeBytes(it.readBytes()) }
            }
            if (!info.exists()) return emptyList()
            java.util.Properties().also {
                info.inputStream().use(it::load)
            }.getProperty("proxy-args")?.split(" ") ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

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
