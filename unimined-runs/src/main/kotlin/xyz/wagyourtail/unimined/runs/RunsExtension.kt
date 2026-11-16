package xyz.wagyourtail.unimined.runs

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.api.util.callDelegate
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import javax.inject.Inject

/**
 * The `runs { }` DSL: a container of named run configurations. Loader
 * plugins and environment binding contribute the defaults — `client` and
 * `server` entries for every Minecraft environment — and users can add
 * custom variants (`run` tasks materialize as `run<Name>`).
 */
abstract class RunsExtension @Inject constructor(objects: ObjectFactory) {

    /** Master switch — `runs { enabled.set(false) }` disables every
     *  materialized run/prepare task (1.x task-level `enabled`
     *  parity). Per-run `enabled` conventions to this. */
    abstract val enabled: org.gradle.api.provider.Property<Boolean>

    /** All declared run configurations. */
    /** env a run was registered *for* — recorded eagerly at registration
     *  because `run.environment` conventions resolve in deferred order and
     *  a foreign env's wire pass would otherwise misread the "main"
     *  default as an intended binding. */
    internal val runEnvironments = mutableMapOf<String, String>()

    /** userdev type key a materialized run binds to — recorded eagerly
     *  alongside [runEnvironments] because `runs.all` callbacks fire
     *  before a `register`ed run's configure action applies
     *  `type.set(...)`. */
    internal val runDeclaredTypes = mutableMapOf<String, String>()

    /**
     * The environment id a run actually launches through — the
     * `runEnvironments`/property binding, redirected through the
     * bound env's `splitClientEnvironment` marker when set: under
     * [MinecraftExtension.splitEnvironmentSourceSets] `main` carries
     * only the common (client∩server) intersection artifact and
     * cannot launch a client, so client-side runs bound to `main`
     * launch through the split `client` env (full client jar +
     * `main`'s output via the implicit-parent combine). Server-side
     * runs stay bound — the common jar is the server's view.
     * Returns `null` for runs no env has claimed yet. Public — IDE
     * emitters and adapters outside this module need the same
     * redirect to pick the env a run materializes on.
     */
    fun effectiveEnvironment(
        project: Project,
        run: RunConfig
    ): String? {
        val bound = runEnvironments[run.name] ?: run.environment.orNull
            ?: return null
        val minecraft = project.extensions
            .findByType(MinecraftExtension::class.java) ?: return bound
        val splitClient = minecraft.environments.findByName(bound)
            ?.splitClientEnvironment?.orNull ?: return bound
        return if (run.side.get() == RunSide.CLIENT) splitClient else bound
    }

    /**
     * Deferred `configFirst` actions per run name — applied ahead of
     * every other configuration action on the run (1.x `configFirst`
     * parity). Applied in declaration order among themselves.
     */
    internal val configFirstActions =
        mutableMapOf<String, MutableList<Action<RunConfig>>>()

    /** Leading entries of [configFirstActions] already applied to the
     *  realized element — guards against double application when a
     *  `configFirst` is declared after the run realized. */
    internal val configFirstApplied = mutableMapOf<String, Int>()

    /**
     * Default auth for every run — `runs { auth { microsoft() } }`
     * applies to all runs unless the run sets its own. Explicit
     * `run.auth`/`run.username` values always win; the env-level spec
     * is only a convention layer.
     */
    val auth: AuthSpec = objects.newInstance(AuthSpec::class.java)

    /** Configure the default [auth] applied to all runs. */
    fun auth(action: Action<AuthSpec>) = action.execute(auth)

    /** Groovy DSL overload — `runs { auth { microsoft() } }`. */
    fun auth(
        @DelegatesTo(value = AuthSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(auth)

    val runs: NamedDomainObjectContainer<RunConfig> =
        objects.domainObjectContainer(RunConfig::class.java)

    init {
        enabled.convention(true)
        runs.configureEach { rc ->
            // `configFirst` actions run ahead of all other config —
            // this is the container's first callback (registered in
            // init), so it beats user `configureEach`/`all` blocks
            // and the `named`/`create` action that realized the
            // element; runs already realized get their tail drained
            // in `configFirst` itself
            configFirstActions[rc.name]?.let { queue ->
                val applied = configFirstApplied[rc.name] ?: 0
                for (i in applied until queue.size) queue[i].execute(rc)
                configFirstApplied[rc.name] = queue.size
            }
            rc.enabled.convention(enabled)
            rc.type.convention(rc.name)
            rc.auth.kind.convention(auth.kind)
            // composite preserves the `username` back-compat bridge —
            // run.username > extension auth.username > AuthSpec's "Dev"
            rc.auth.username.convention(rc.username.orElse(auth.username))
            rc.auth.provider.convention(auth.provider)
        }
    }

    /**
     * Configure [name] ahead of all other configuration — 1.x
     * `configFirst` parity (the provider default-setup hook). The
     * action applies when the run realizes, before `named`/`create`
     * actions, user `configureEach`/`all` blocks, and this
     * extension's own conventions, so subsequently-declared
     * configuration can override what it sets. `configFirst` calls
     * run in declaration order among themselves; the run is created
     * if absent.
     *
     * Declare before other configuration of the run — a
     * `configFirst` issued after the run already realized applies
     * immediately (after the actions that already ran); unlike 1.x's
     * fully-deferred model it can't retroactively precede them.
     */
    fun configFirst(name: String, action: Action<RunConfig>) {
        val queue = configFirstActions.getOrPut(name) { mutableListOf() }
        queue.add(action)
        val rc = runs.maybeCreate(name)
        // element already realized → the configureEach drain won't
        // re-fire; apply the un-applied tail now
        val applied = configFirstApplied[name] ?: 0
        for (i in applied until queue.size) queue[i].execute(rc)
        configFirstApplied[name] = queue.size
    }

    /** Groovy DSL overload — `runs { configFirst("client") { ... } }`. */
    fun configFirst(
        name: String,
        @DelegatesTo(value = RunConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = configFirst(name, Action { rc -> action.delegatingTo(rc) })

    /** Configure a run by name — creates it if absent. */
    fun named(name: String, action: Action<RunConfig>) {
        runs.maybeCreate(name).let(action::execute)
    }

    /** Groovy DSL overload — `runs { named("client") { ... } }`. */
    fun named(
        name: String,
        @DelegatesTo(value = RunConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(runs.maybeCreate(name))

    /** Declare a custom run configuration. */
    fun create(name: String, action: Action<RunConfig>) {
        action.execute(runs.create(name))
    }

    /** Groovy DSL overload — `runs { create("ci") { ... } }`. */
    fun create(
        name: String,
        @DelegatesTo(value = RunConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(runs.create(name))

    /** `runs { named(...) { ... } }` — configure the run container. */
    fun runs(action: Action<NamedDomainObjectContainer<RunConfig>>) =
        action.execute(runs)

    /** Groovy DSL overload — `runs { client { mainClass = 'x' } }`. */
    fun runs(
        @DelegatesTo(value = NamedDomainObjectContainer::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegate = runs
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
    }

}
