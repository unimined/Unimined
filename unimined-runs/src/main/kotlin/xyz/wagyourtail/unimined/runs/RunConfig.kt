package xyz.wagyourtail.unimined.runs

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.delegatingTo
import javax.inject.Inject

/** Which side a run targets. */
enum class RunSide { CLIENT, SERVER }

/**
 * One named run configuration — materialized into a `run<Name>` task.
 * Un-set fields (main class, args, classpath, natives) default from the
 * environment's launch plan at execution time.
 */
abstract class RunConfig @Inject constructor(
    private val name: String,
    objects: ObjectFactory,
    private val providers: ProviderFactory
) : Named {

    override fun getName(): String = name

    /** Opt this run out — the materialized `run<Name>`/`prepare*`
     *  tasks skip when false. Conventions to `runs.enabled` (1.x
     *  task-level `enabled` parity). */
    abstract val enabled: Property<Boolean>

    /** The environment (`SourceSet` name) this run launches. */
    abstract val environment: Property<String>

    /**
     * Loader run-config key this run resolves against — the
     * `runs.<type>` entry in forge userdev-style profiles
     * (`client`, `server`, `data`, `junit`, `gameTestServer`...).
     * Defaults to the run's name; set it to bind a differently-named
     * run to a declared loader run shape:
     * `runs.named("myDatagen") { type.set("data") }`.
     */
    abstract val type: Property<String>

    /**
     * Depend on [task] before this run — `beforeRun(preBuildTask)`
     * adds `run<Name>.dependsOn(task)`.
     */
    fun beforeRun(task: org.gradle.api.tasks.TaskProvider<*>) {
        beforeRunDeps += task
    }

    /** Register an ad-hoc `preRun<Name><i>` task configured by
     *  [action] and run it before this run. */
    fun beforeRun(action: Action<org.gradle.api.Task>) {
        beforeRunActions += action
    }

    /** Groovy DSL twin of [beforeRun]. */
    fun beforeRun(
        @DelegatesTo(
            value = org.gradle.api.Task::class,
            strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        beforeRunActions += Action { t -> action.delegatingTo(t) }
    }

    internal val beforeRunDeps =
        mutableListOf<org.gradle.api.tasks.TaskProvider<*>>()
    internal val beforeRunActions =
        mutableListOf<Action<org.gradle.api.Task>>()

    abstract val side: Property<RunSide>

    /**
     * Launch against the production-namespace classpath (the
     * compiled/remapped mod jar + `*Remapped` mod views + the raw
     * MC artifact) instead of the dev classpath — 1.x
     * `runObfClient`/`runObfServer` parity. Default false.
     */
    abstract val prod: Property<Boolean>

    /**
     * Extra classpath entries appended to this run's launch
     * classpath — per-run libs/tools (loom/MDG run-dependency
     * parity). For maven coordinates declare them on the
     * `run<Name>Dependencies` configuration instead.
     */
    val additionalRuntimeClasspath: ConfigurableFileCollection =
        objects.fileCollection()

    /**
     * Extra mod jars staged into this run's mod folder by
     * `prepare<Name>` — loaders whose descriptor declares the
     * `modsDir` capability copy mod jars into `runDir/<dir>` (`mods/`
     * on forge/conversion-family, `plugins/` on bukkit). Files are
     * copied verbatim — never remapped — and are NOT placed on the
     * launch classpath: the loader's own folder discovery is what
     * loads them. Use for jars that must run under the loader's
     * classloader instead of the system one — e.g. a ModLauncher
     * `ITransformationService` carrier (mixin-engine coremods) whose
     * service-loading/classloader assumptions break on `-cp`.
     *
     * On loaders that discover classpath mods natively (`modGroupsEnv`
     * /`classpathGroups` — forge-family `ClasspathLocator` +
     * `MOD_CLASSES`), this is the ONLY staged content: the project
     * jar and `*Direct` mod views already register through the
     * classpath, and a folder copy would double-register them.
     * Ignored where the loader stages no mod folder.
     */
    val modFolderFiles: ConfigurableFileCollection =
        objects.fileCollection()

    /** Working directory — defaults to `run/<environment>-<name>`. */
    abstract val workingDirectory: Property<String>

    /** Main-class override — defaults from launch metadata + mutators. */
    abstract val mainClass: Property<String>

    /**
     * Java agent jars attached to the launch — each renders as
     * `-javaagent:<path>` ahead of [jvmArgs]. File-collection typed
     * so task-produced agents can be wired with dependencies.
     */
    abstract val javaagents: ConfigurableFileCollection

    /** Extra JVM args appended after the version document's. */
    abstract val jvmArgs: ListProperty<String>

    /** Extra program args appended after the version document's. */
    abstract val programArgs: ListProperty<String>

    /** Extra environment variables for the launched process. */
    abstract val environmentVariables: MapProperty<String, String>

    /**
     * `-D` JVM system properties — the typed alternative to raw
     * `jvmArgs.add("-Dk=v")` (loom/MDG `systemProperties` parity).
     * Rendered ahead of `jvmArgs`, so an explicit `-D` there still
     * wins. Distinct from [environmentVariables], which sets process
     * env.
     */
    abstract val systemProperties: MapProperty<String, String>

    /**
     * `${key}` substitution values applied to `jvmArgs`/`programArgs`/
     * `systemProperties`/`environmentVariables` at launch — 1.x
     * `RunConfig.setProperty` parity. Entries merge over the
     * environment-level defaults (`game_directory`,
     * `natives_directory`, `assets_root`, `version_type`, ...) and
     * over transform-mutator keys (forge's `modules`,
     * `minecraft_classpath_file`, ...), so an explicit user entry
     * wins. Unknown `${key}` placeholders warn and substitute `""`.
     */
    abstract val launchProperties: MapProperty<String, String>

    /** Declare a `${key}` substitution — `programArgs.add("--user=\${name}")`. */
    fun setProperty(key: String, value: String) =
        launchProperties.put(key, value)

    /** Lazy variant — the provider resolves at launch, not configure time. */
    fun setProperty(key: String, value: Provider<out String>) =
        launchProperties.put(key, value)

    /**
     * Lazy supplier variant — 1.x's `() -> String` storage shape;
     * evaluated at launch (task-input time).
     */
    fun setProperty(key: String, value: () -> String) =
        launchProperties.put(key, providers.provider { value() })

    /**
     * Groovy lazy variant — `setProperty("name") { expr }` delegates
     * to this run config and evaluates at launch.
     */
    fun setProperty(
        key: String,
        @DelegatesTo(
            value = RunConfig::class,
            strategy = Closure.DELEGATE_FIRST) value: Closure<String>
    ) {
        value.delegate = this
        value.resolveStrategy = Closure.DELEGATE_FIRST
        // `?: ""` tolerates null/GString — substitution wants Strings
        launchProperties.put(key,
            providers.provider { value.call()?.toString() ?: "" })
    }

    /**
     * Root log level for a generated log4j2 dev config —
     * `logLevel.set("DEBUG")` materializes a `log4j2.xml` under the
     * task's temp dir and passes `-Dlog4j.configurationFile`
     * (loom dev-log parity). Ignored when [loggingConfigFile] is set.
     */
    abstract val logLevel: Property<String>

    /** Explicit log4j2 config file — takes precedence over the
     *  `logLevel`-generated default. */
    abstract val loggingConfigFile: RegularFileProperty

    /**
     * IDE run-configuration display/file name — defaults to the run
     * name (loom/MDG `ideName` parity).
     */
    abstract val ideName: Property<String>

    /** IDE-side folder/group label for this run's configuration —
     *  honored by emitters that support grouping. */
    abstract val ideFolderName: Property<String>

    /**
     * `false` suppresses this run's IDE run-configuration emission
     * entirely — `genIdeRuns`/`genIdeaRuns`/`genVsCodeRuns` skip it
     * (loom `ideConfigGenerated` parity).
     */
    abstract val ideConfigGenerated: Property<Boolean>

    /**
     * Mark this run's IDE configuration single-instance — IntelliJ
     * refuses a second concurrent launch of the same config
     * (`singleton="true"`, NG `singleInstance` parity). Currently
     * honored by the IDEA emitter only; no effect on `run<Name>`
     * task execution.
     */
    abstract val singleInstance: Property<Boolean>

    /**
     * Mark this run as a JUnit test run — materializes a Gradle
     * `Test` task (`test<Name>`) alongside `run<Name>`: JUnit-platform
     * discovery replaces the normal main-class launch, and the
     * loader's launch config rides an args-file exposed via the
     * `fml.junit.argsfile` system property (NG `isJUnit`/`unitTest`
     * parity). Conventions to `true` when [type] is `"junit"` on a
     * loader whose descriptor declares the `gametestJunit`
     * capability; set explicitly on any other run.
     */
    @Suppress("AcronymName")
    abstract val unitTest: Property<Boolean>

    /**
     * SourceSets supplying test classes and classpath for an
     * [unitTest] run — their `output` lands on the test classpath and
     * their `output.classesDirs` become `testClassesDirs`
     * (NG `unitTestSources` parity). Conventions to the `test`
     * source set when present.
     */
    val unitTestSources: org.gradle.api.DomainObjectSet<SourceSet> =
        objects.domainObjectSet(SourceSet::class.java)

    /**
     * Names of the environment's `mods { "<id>" { } }` groups this run
     * loads — their source-set outputs and `modFiles` join the launch
     * classpath, and the groups serialize for loaders that group
     * classpath entries by mod (`fabric.classPathGroups` on
     * fabric-family, `MOD_CLASSES` entries on forge-family — Loom
     * `mods {}` / NFRT parity). Empty (the convention) loads every
     * declared group.
     */
    abstract val loadedMods: SetProperty<String>

    /** Select which declared `mods {}` groups this run loads. */
    fun loadedMods(vararg names: String) = loadedMods.set(names.toSet())

    /**
     * Data piped to the launched process's stdin. For dedicated
     * servers, console commands read from stdin queue until the server
     * ticks — so `stdin.set("stop\n")` boots the server and
     * auto-stops it right after "Done" (test/smoke-launch automation).
     */
    abstract val stdin: Property<String>

    /**
     * Offline-mode player name — back-compat shorthand feeding
     * `auth.username`'s convention; `auth { offline(it) }` is the
     * typed form.
     */
    abstract val username: Property<String>

    /**
     * Per-run auth — `auth { microsoft() }` / `offline(name)` /
     * `custom(provider)`. See [AuthSpec].
     */
    val auth: AuthSpec = objects.newInstance(AuthSpec::class.java)

    /** Configure this run's [auth]. */
    fun auth(action: Action<AuthSpec>) = action.execute(auth)

    /** Groovy DSL overload — `auth { microsoft() }` on one run. */
    fun auth(
        @DelegatesTo(value = AuthSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(auth)

    /** Rule features to enable (`is_demo_user`, `has_custom_resolution`). */
    abstract val features: SetProperty<String>

    /** Optional window resolution forwarded as `--width`. */
    abstract val width: Property<Int>

    /** Optional window resolution forwarded as `--height`. */
    abstract val height: Property<Int>

    /** Download asset objects for the launch's asset index (default on). */
    abstract val downloadAssets: Property<Boolean>

    /**
     * Copy another run's spec into this one — every property is
     * wired as a *convention* off the source's provider, so explicit
     * sets on this run still win and un-configured fields follow the
     * source (including mutations made after `from` runs — copy is
     * lazy, not a snapshot). `name` is never copied.
     *
     * ```
     * runs.create("debugClient") {
     *     from(runs.named("client"))
     *     jvmArgs.add("-Xmx4G")
     * }
     * ```
     *
     * `beforeRun` contributions are not inherited — they are per-run
     * wiring, not spec state; re-declare them on the derived run.
     */
    fun from(other: RunConfig) {
        environment.convention(other.environment)
        side.convention(other.side)
        prod.convention(other.prod)
        type.convention(other.type)
        workingDirectory.convention(other.workingDirectory)
        mainClass.convention(other.mainClass)
        javaagents.from(other.javaagents)
        jvmArgs.convention(other.jvmArgs)
        programArgs.convention(other.programArgs)
        environmentVariables.convention(other.environmentVariables)
        systemProperties.convention(other.systemProperties)
        launchProperties.convention(other.launchProperties)
        logLevel.convention(other.logLevel)
        loggingConfigFile.convention(other.loggingConfigFile)
        ideName.convention(other.ideName)
        ideFolderName.convention(other.ideFolderName)
        ideConfigGenerated.convention(other.ideConfigGenerated)
        singleInstance.convention(other.singleInstance)
        unitTest.convention(other.unitTest)
        // live view of the source's set — `addAllLater` reads it at
        // use time, keeping the "lazy, not a snapshot" contract
        unitTestSources.addAllLater(providers.provider { other.unitTestSources })
        loadedMods.convention(other.loadedMods)
        stdin.convention(other.stdin)
        username.convention(other.username)
        auth.kind.convention(other.auth.kind)
        auth.username.convention(other.auth.username)
        auth.provider.convention(other.auth.provider)
        features.convention(other.features)
        width.convention(other.width)
        height.convention(other.height)
        downloadAssets.convention(other.downloadAssets)
        additionalRuntimeClasspath.from(other.additionalRuntimeClasspath)
        modFolderFiles.from(other.modFolderFiles)
    }

    /** `from(runs.named("client"))` — each property flat-mapped
     *  lazily, so the source run doesn't need to exist yet. Copies the
     *  same fields as the [RunConfig] overload of [from]; `beforeRun`
     *  contributions are not inherited — re-declare them on the
     *  derived run. */
    fun from(other: org.gradle.api.provider.Provider<RunConfig>) {
        environment.convention(other.flatMap { it.environment })
        side.convention(other.flatMap { it.side })
        prod.convention(other.flatMap { it.prod })
        workingDirectory.convention(other.flatMap { it.workingDirectory })
        mainClass.convention(other.flatMap { it.mainClass })
        javaagents.from(other.map { it.javaagents })
        jvmArgs.convention(other.flatMap { it.jvmArgs })
        programArgs.convention(other.flatMap { it.programArgs })
        environmentVariables.convention(other.flatMap { it.environmentVariables })
        systemProperties.convention(other.flatMap { it.systemProperties })
        launchProperties.convention(other.flatMap { it.launchProperties })
        logLevel.convention(other.flatMap { it.logLevel })
        loggingConfigFile.convention(other.flatMap { it.loggingConfigFile })
        ideName.convention(other.flatMap { it.ideName })
        ideFolderName.convention(other.flatMap { it.ideFolderName })
        ideConfigGenerated.convention(other.flatMap { it.ideConfigGenerated })
        singleInstance.convention(other.flatMap { it.singleInstance })
        type.convention(other.flatMap { it.type })
        unitTest.convention(other.flatMap { it.unitTest })
        unitTestSources.addAllLater(other.map { it.unitTestSources })
        loadedMods.convention(other.flatMap { it.loadedMods })
        stdin.convention(other.flatMap { it.stdin })
        username.convention(other.flatMap { it.username })
        auth.kind.convention(other.flatMap { it.auth.kind })
        auth.username.convention(other.flatMap { it.auth.username })
        auth.provider.convention(other.flatMap { it.auth.provider })
        features.convention(other.flatMap { it.features })
        width.convention(other.flatMap { it.width })
        height.convention(other.flatMap { it.height })
        downloadAssets.convention(other.flatMap { it.downloadAssets })
        additionalRuntimeClasspath.from(
            other.map { it.additionalRuntimeClasspath })
        modFolderFiles.from(other.map { it.modFolderFiles })
    }

    init {
        environment.convention(SourceSet.MAIN_SOURCE_SET_NAME)
        // camelCase-boundary match: the wired `server`/`<env>Server`/
        // `prodServer`/`gameTestServer` names all end in `Server`;
        // `equals` alone would leave prefixed servers client-sided
        val defaultSide =
            if (name.equals("server", ignoreCase = true) ||
                name.endsWith("Server")) RunSide.SERVER else RunSide.CLIENT
        side.convention(defaultSide)
        // no mainClass convention — absent until metadata/mutators fill it
        jvmArgs.convention(emptyList())
        // dedicated servers default headless; opt out via `programArgs.set(...)`
        programArgs.convention(
            if (defaultSide == RunSide.SERVER) listOf("nogui") else emptyList()
        )
        environmentVariables.convention(emptyMap())
        systemProperties.convention(emptyMap())
        launchProperties.convention(emptyMap())
        ideName.convention(name)
        ideConfigGenerated.convention(true)
        singleInstance.convention(false)
        prod.convention(false)
        // `username` is the offline-name bridge INTO auth.username —
        // it stays unset unless the user assigns it, so an env-level
        // `runs { auth { } }` default can still show through below it;
        // an explicit `auth.username` (`auth { offline("x") }`) wins.
        auth.username.convention(username.orElse("Dev"))
        features.convention(emptySet())
        downloadAssets.convention(true)
    }

}
