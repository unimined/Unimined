package xyz.wagyourtail.unimined.runs.task

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
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.produceCached
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.mclauncher.*
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.runs.AuthSpec
import xyz.wagyourtail.unimined.runs.RunConfig
import xyz.wagyourtail.unimined.runs.RunSide
import xyz.wagyourtail.unimined.runs.internal.LaunchVerifier
import java.io.File
import javax.inject.Inject

/**
 * Executes a Minecraft launch: resolves the version chain, extracts
 * natives, prepares assets, builds the launch plan via `mc-launcher`,
 * and forks the JVM. All metadata work happens at execution time so the
 * task is configuration-cache safe.
 */
@UntrackedTask(because = "runs the game — a side-effecting process fork, not a build output")
abstract class MinecraftRunTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val objects: org.gradle.api.model.ObjectFactory
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

    /** Java agent jars — rendered as `-javaagent:<path>` ahead of
     *  `jvmArgs`. File-typed so agent-producing tasks are tracked
     *  dependencies. */
    @get:InputFiles
    @get:Classpath
    abstract val javaagents: ConfigurableFileCollection

    /** Agent jar paths contributed by transform run mutators (the
     *  `RunConfigSpec.javaagents` channel — strings, not files, since
     *  mutators resolve them inside providers). */
    @get:Input
    abstract val mutatedJavaagents: ListProperty<String>

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

    /** `-D` JVM system properties — [RunConfig.systemProperties];
     *  rendered ahead of `jvmArgs` so an explicit `-D` there wins. */
    @get:Input
    abstract val systemProperties: MapProperty<String, String>

    /** Root level for the generated log4j2 dev config —
     *  [RunConfig.logLevel]; ignored when [loggingConfigFile] set. */
    @get:Input
    @get:Optional
    abstract val logLevel: Property<String>

    /** Explicit log4j2 config — [RunConfig.loggingConfigFile]. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val loggingConfigFile: RegularFileProperty

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

    /** [RunConfig.auth] kind — session resolution happens in
     *  [session] at execution time; nothing token-shaped is an input. */
    @get:Input
    abstract val authKind: Property<AuthSpec.Kind>

    /** `CUSTOM` auth provider — internal-only: it runs at execution
     *  and must never reach inputs or the config cache. */
    @get:Internal
    abstract val authProvider: Property<xyz.wagyourtail.unimined.mclauncher.AuthProvider>

    /** `MICROSOFT` credential-store file — the store is read at
     *  execution (0600'd under the unimined global cache). Path only
     *  is an input; contents never are. */
    @get:Input
    @get:Optional
    abstract val authStorePath: Property<String>

    /** `unimined.auth.encrypt` — opt-in AES store (OS keyring key,
     *  resolved at execution; explicit failure, never plaintext
     *  fallback). */
    @get:Input
    abstract val authEncrypt: Property<Boolean>

    /** `unimined.auth.storeCredentials` — when `false`, refreshed
     *  sessions are used in memory only and never written back to the
     *  store (defaults to true). */
    @get:Input
    abstract val authStoreCredentials: Property<Boolean>

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

    /** `${key}` substitutions declared on the run itself
     *  ([RunConfig.setProperty]/[RunConfig.launchProperties]) —
     *  applied last so an explicit user entry wins over env defaults
     *  and mutator keys (1.x parity: user `config` blocks ran after
     *  provider `configFirst` setup). */
    @get:Input
    abstract val userLaunchProperties: MapProperty<String, String>

    /**
     * Print the fully-resolved launch (main class, jvm/program args,
     * env, classpath) instead of exec'ing it — verification/debug
     * surface; the run wiring itself (mutators, natives, assets) is
     * still evaluated.
     */
    @get:Input
    abstract val dryRun: Property<Boolean>

    /**
     * `-Punimined.run.verify=true` — audit the assembled launch plan
     * (classpath entries exist, resolved main class is on the
     * classpath, loader-required resources present, agents/asset
     * index on disk) and return without exec'ing. Fails the task on
     * violations a real launch would hit at class-load.
     */
    @get:Input
    abstract val verify: Property<Boolean>

    /**
     * Emit runnable `<base>.sh`/`.cmd` launch scripts into the run
     * directory instead of exec'ing — the `genLaunchScript<Name>`
     * tasks (MDG `createLaunchScripts` parity). The same resolved
     * classpath/args/env as a real launch is baked into the scripts.
     */
    @get:Input
    abstract val emitScripts: Property<Boolean>

    /** Script file base name — wiring sets `run<Name>`; defaults to
     *  the task name. */
    @get:Input
    @get:Optional
    abstract val scriptBaseName: Property<String>

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
        mutatedJavaagents.convention(emptyList())
        mutatedJvmArgs.convention(emptyList())
        mutatedProgramArgs.convention(emptyList())
        mutatedEnvironment.convention(emptyMap())
        environmentVariables.convention(emptyMap())
        systemProperties.convention(emptyMap())
        launchProperties.convention(emptyMap())
        mutatedLaunchProperties.convention(emptyMap())
        userLaunchProperties.convention(emptyMap())
        features.convention(emptySet())
        username.convention("Dev")
        authKind.convention(AuthSpec.Kind.OFFLINE)
        authEncrypt.convention(false)
        authStoreCredentials.convention(true)
        downloadAssets.convention(true)
        offline.convention(false)
        dryRun.convention(false)
        verify.convention(false)
        emitScripts.convention(false)
        useToolchains.convention(true)
    }

    @TaskAction
    fun launch() {
        val requiredJava = requiredJavaVersion.orNull
        if (!useToolchains.get() && requiredJava != null &&
            org.gradle.api.JavaVersion.current() <
                org.gradle.api.JavaVersion.toVersion(requiredJava)) {
            project.failProblem("runs/java-floor",
                "unimined: '$path' requires Java $requiredJava but " +
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
            // the game resolves the index at <assetsDir>/indexes/<id>.json —
            // object downloads alone leave the index file absent and the
            // client silently loads no assets
            val indexId = assetIndexId.get()
            val indexTarget = assetsRoot.resolve("indexes/$indexId.json")
            indexTarget.produceCached { tmp ->
                tmp.toFile().writeText(indexJson)
            }
            val downloaded = xyz.wagyourtail.unimined.api.util.Perf.span(
                "assets", "index" to indexId
            ) {
                Assets.downloadObjects(indexJson, assetsRoot, offline.get())
            }
            if (downloaded > 0) logger.lifecycle("Unimined: fetched $downloaded asset objects")
            // pre-1.6 games load sounds/music from <gamedir>/resources;
            // map_to_resources indexes must be materialized there before
            // launch or the in-game downloader hits the dead S3 bucket
            if (side.get() != RunSide.SERVER && Assets.mapsToResources(indexJson)) {
                val resourcesDir = runDirectory.get().asFile.toPath().resolve("resources")
                val copied = xyz.wagyourtail.unimined.api.util.Perf.span(
                    "assets.resources", "index" to indexId
                ) {
                    Assets.copyToResources(indexJson, assetsRoot, resourcesDir)
                }
                if (copied > 0) logger.lifecycle("Unimined: mapped $copied assets into resources/")
            }
        }

        // monolithic legacy ASM (launchwrapper-era `asm-all` /
        // `asm-debug-all`) conflicts with the modular `asm` artifact
        // on loader-managed classpaths (fabric knot rejects duplicate
        // ASM packages); drop it when a modern `asm-<v>.jar` is
        // present OR the loader is fabric (its jar already bundles
        // org.objectweb.asm) — launchwrapper-only envs keep theirs.
        // LaunchWrapper-era launches are exempt: FML's deobf tweaker
        // needs asm-debug-all's RemappingClassAdapter while runtime
        // deps may still drag in modular asm — LaunchWrapper
        // tolerates split packages (first-wins), so keep both.
        var runtimeFiles = runtimeClasspath.files.toList()
        val launchwrapperEra = (mainClass.orNull ?: document.mainClass)
            ?.startsWith("net.minecraft.launchwrapper.") == true
        // forge-family `modGroupsEnv` (MOD_CLASSES `id%%dir` pairs) is
        // the ModLauncher-era mod-discovery channel — a dir it names
        // must not also sit on java.class.path: ClasspathLocator scans
        // -cp, finds the same mods.toml dir, and registers the mod a
        // second time (LoadingModList duplicate-key crash). Strip
        // matching dirs by path. LaunchWrapper-era runs exempt — FML's
        // own dev layout puts mod classes on -cp there.
        val modClassesDirs = (mutatedEnvironment.get() +
                environmentVariables.get()).values.flatMap { v ->
            v.split(File.pathSeparatorChar)
                .filter { "%%" in it }
                .map { it.substringAfter("%%") }
        }.toSet()
        if (!launchwrapperEra && modClassesDirs.isNotEmpty())
            runtimeFiles = runtimeFiles.filterNot {
                it.absolutePath in modClassesDirs
            }
        val loaderAsm = !launchwrapperEra && runtimeFiles.any {
            it.name.matches(Regex("""asm-\d.*\.jar""")) ||
                it.name.startsWith("fabric-loader-")
        }
        val dedupedClasspath = if (loaderAsm)
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

        // env defaults < mutator keys < user `setProperty` entries —
        // last write wins, matching 1.x's single shared map where
        // user `config` ran after provider `configFirst` setup
        val subst = launchProperties.get() + mutatedLaunchProperties.get() +
                userLaunchProperties.get()

        val agentArgs = (javaagents.files.map { it.absolutePath } +
                mutatedJavaagents.get()).map { "-javaagent:$it" }

        // `-D` system properties — declared entries verbatim, plus
        // `-Dlog4j.configurationFile`: the explicit file wins, else a
        // `logLevel`-generated default dev config
        val logConfig = loggingConfigFile.orNull?.asFile?.toPath()
            ?: logLevel.orNull?.let { level ->
                temporaryDir.toPath().resolve("log4j2.xml").also {
                    java.nio.file.Files.createDirectories(it.parent)
                    java.nio.file.Files.write(
                        it, defaultLog4j2(level).toByteArray())
                }
            }
        val systemPropertyArgs = systemProperties.get()
            .map { (k, v) -> "-D$k=$v" } +
            listOfNotNull(logConfig?.let {
                "-Dlog4j.configurationFile=${it.toFile().absolutePath}"
            })

        val allJvmArgs = subst.resolveLaunchArgs(
            plan.jvmArgs + "-Djava.library.path=$nativesDir" +
                    systemPropertyArgs +
                    agentArgs + jvmArgs.get() + mutatedJvmArgs.get()
        )
        // the version document's `game` args are client-only
        // (--username/--accessToken/...); servers take user args only
        val allArgs = subst.resolveLaunchArgs(
            (if (side.get() == RunSide.SERVER) emptyList() else plan.programArgs) +
                    programArgs.get() + mutatedProgramArgs.get()
        )
        val allEnv = plan.environment +
                subst.resolveLaunchArgs(
                    mutatedEnvironment.get() + environmentVariables.get()
                )

        if (verify.get()) {
            val findings = LaunchVerifier.verify(
                mainClass = resolvedMainClass,
                classpath = dedupedClasspath,
                jvmArgs = allJvmArgs,
                javaagents = javaagents.files.toList(),
                assetIndex = assetIndexFile.orNull?.asFile,
            )
            if (findings.isEmpty()) {
                println("[unimined:verify] $path OK — " +
                        "mainClass=$resolvedMainClass, " +
                        "${dedupedClasspath.size} classpath entries")
            } else {
                findings.forEach { f ->
                    println("[unimined:verify] " +
                            (if (f.error) "FAIL" else "WARN") +
                            " ${f.message}")
                }
                findings.firstOrNull { it.error }?.let {
                    error("launch verification failed for $path " +
                            "(${findings.count { f -> f.error }} errors)")
                }
            }
            return
        }

        if (emitScripts.get()) {
            val dir = runDirectory.get().asFile
            val base = scriptBaseName.orNull ?: name
            xyz.wagyourtail.unimined.runs.internal.LaunchScriptFiles.write(
                dir, base,
                xyz.wagyourtail.unimined.runs.internal.LaunchScriptFiles.Launch(
                    javaExecutable = javaExecutable.orNull ?: "java",
                    mainClass = resolvedMainClass,
                    classpath = dedupedClasspath.map { it.absolutePath },
                    jvmArgs = allJvmArgs,
                    programArgs = allArgs,
                    environment = allEnv,
                )
            ).forEach {
                logger.lifecycle("[unimined] wrote ${it.absolutePath}")
            }
            return
        }

        if (dryRun.get()) {
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
            exec.classpath = objects.fileCollection().from(dedupedClasspath)
            exec.jvmArgs(allJvmArgs)
            exec.args(allArgs)
            exec.environment(allEnv)
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
            project.reportProblem("runs/launch-property",
                "unimined: launch property '\${$it}' unset — " +
                    "substituting \"\"")
        }
        return (this + missing.associateWith { "" }).resolveArgs(args)
    }

    private fun Map<String, String>.resolveLaunchArgs(
        values: Map<String, String>
    ): Map<String, String> =
        values.mapValues { (_, v) -> resolveLaunchArgs(listOf(v)).single() }

    private fun session(): Session = when (authKind.get()) {
        AuthSpec.Kind.OFFLINE -> OfflineAuth(username.get()).session()
        AuthSpec.Kind.CUSTOM -> authProvider.get().session()
        AuthSpec.Kind.MICROSOFT -> try {
            val path = java.nio.file.Path.of(authStorePath.get())
            val store: xyz.wagyourtail.unimined.mclauncher.SessionStore =
                if (authEncrypt.get()) {
                    xyz.wagyourtail.unimined.mclauncher.EncryptedCredentialStore(
                        path,
                        xyz.wagyourtail.unimined.mclauncher
                            .EncryptedCredentialStore.JavaKeyringProvider()
                            .key()
                    )
                } else {
                    xyz.wagyourtail.unimined.mclauncher.CredentialStore(path)
                }
            xyz.wagyourtail.unimined.mclauncher.MicrosoftAuth(
                store, username.get(),
                storeOnRefresh = authStoreCredentials.get()
            ).session()
        } catch (e: IllegalStateException) {
            project.failProblem("runs/auth", "unimined: ${e.message!!}")
        }
    }

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

    /** Minimal dev log4j2 config — console appender with the run's
     *  declared root [level] (loom's generated dev config shape). */
    private fun defaultLog4j2(level: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="SysOut" target="SYSTEM_OUT">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level] (%logger{1}) %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="$level">
            <AppenderRef ref="SysOut"/>
        </Root>
    </Loggers>
</Configuration>
"""

}
