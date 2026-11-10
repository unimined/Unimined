package xyz.wagyourtail.unimined.runs

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSet
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
    objects: ObjectFactory
) : Named {

    override fun getName(): String = name

    /** The environment (`SourceSet` name) this run launches. */
    abstract val environment: Property<String>

    abstract val side: Property<RunSide>

    /**
     * Launch against the production-namespace classpath (the
     * compiled/remapped mod jar + `*Remapped` mod views + the raw
     * MC artifact) instead of the dev classpath — 1.x
     * `runObfClient`/`runObfServer` parity. Default false.
     */
    abstract val prod: Property<Boolean>

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
     */
    fun from(other: RunConfig) {
        environment.convention(other.environment)
        side.convention(other.side)
        prod.convention(other.prod)
        workingDirectory.convention(other.workingDirectory)
        mainClass.convention(other.mainClass)
        javaagents.from(other.javaagents)
        jvmArgs.convention(other.jvmArgs)
        programArgs.convention(other.programArgs)
        environmentVariables.convention(other.environmentVariables)
        stdin.convention(other.stdin)
        username.convention(other.username)
        auth.kind.convention(other.auth.kind)
        auth.username.convention(other.auth.username)
        auth.provider.convention(other.auth.provider)
        features.convention(other.features)
        width.convention(other.width)
        height.convention(other.height)
        downloadAssets.convention(other.downloadAssets)
    }

    /** `from(runs.named("client"))` — each property flat-mapped
     *  lazily, so the source run doesn't need to exist yet. */
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
        stdin.convention(other.flatMap { it.stdin })
        username.convention(other.flatMap { it.username })
        auth.kind.convention(other.flatMap { it.auth.kind })
        auth.username.convention(other.flatMap { it.auth.username })
        auth.provider.convention(other.flatMap { it.auth.provider })
        features.convention(other.flatMap { it.features })
        width.convention(other.flatMap { it.width })
        height.convention(other.flatMap { it.height })
        downloadAssets.convention(other.flatMap { it.downloadAssets })
    }

    init {
        environment.convention(SourceSet.MAIN_SOURCE_SET_NAME)
        val defaultSide =
            if (name.equals("server", ignoreCase = true)) RunSide.SERVER else RunSide.CLIENT
        side.convention(defaultSide)
        // no mainClass convention — absent until metadata/mutators fill it
        jvmArgs.convention(emptyList())
        // dedicated servers default headless; opt out via `programArgs.set(...)`
        programArgs.convention(
            if (defaultSide == RunSide.SERVER) listOf("nogui") else emptyList()
        )
        environmentVariables.convention(emptyMap())
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
