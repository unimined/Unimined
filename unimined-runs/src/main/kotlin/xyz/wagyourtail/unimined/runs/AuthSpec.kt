package xyz.wagyourtail.unimined.runs

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import xyz.wagyourtail.unimined.mclauncher.AuthProvider
import javax.inject.Inject

/**
 * Per-run auth specification — how the launch's [xyz.wagyourtail.unimined.mclauncher.Session]
 * is produced. Replaces the 1.x project-global `runs.auth`: each
 * [RunConfig] carries its own typed spec, so a `client` run can log
 * in while a `server` run stays offline.
 *
 * Session materialization happens at task-execution time only — no
 * token ever lands on the configuration model or the config cache.
 *
 * ```
 * runs.named("client") { auth { microsoft() } }
 * runs.named("server") { auth { offline("DevServer") } }
 * runs.configureEach { auth { custom(MyProvider()) } }
 * ```
 */
abstract class AuthSpec @Inject constructor(objects: ObjectFactory) {

    /** Session kind — defaults to [Kind.OFFLINE]. */
    enum class Kind { OFFLINE, MICROSOFT, CUSTOM }

    abstract val kind: Property<Kind>

    /** Player name for [Kind.OFFLINE] sessions. */
    abstract val username: Property<String>

    /**
     * Custom [AuthProvider] for [Kind.CUSTOM] — the extension point
     * for third-party auth (Ely.by, CI token injection). Never
     * persisted: the provider runs at task execution.
     */
    @get:Internal
    abstract val provider: Property<AuthProvider>

    /** Offline session named [name]. */
    fun offline(name: String) {
        kind.set(Kind.OFFLINE)
        username.set(name)
    }

    /** Microsoft account login — consumed from the credential store
     *  (populated by `:loginMicrosoft`) with silent refresh. */
    fun microsoft() {
        kind.set(Kind.MICROSOFT)
    }

    /** Session produced by [provider] at execution time. */
    fun custom(provider: AuthProvider) {
        kind.set(Kind.CUSTOM)
        this.provider.set(provider)
    }

    /**
     * CI/headless auth — session read from `UNIMINED_AUTH_*`
     * environment variables at execution time ([prefix] overridable).
     * No credential is stored or configured.
     */
    fun injected(prefix: String = "UNIMINED_AUTH_") {
        custom(xyz.wagyourtail.unimined.mclauncher.InjectedSessionAuth(prefix))
    }

    init {
        kind.convention(Kind.OFFLINE)
        username.convention("Dev")
    }

}
