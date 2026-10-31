package xyz.wagyourtail.unimined.publish

import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.provider.Property

/**
 * `uniminedPublish { }` DSL — controls which source-set/loader
 * environment artifacts become publishable components.
 *
 * ```kotlin
 * uniminedPublish {
 *     javaPublication()        // opt-in ordinary `java` component
 *     environment("main") { coordinate("com.example", "my-mod") }
 * }
 * ```
 */
abstract class PublishExtension {

    /**
     * Also publish the ordinary `components.java` component — off by
     * default (plan §11: never auto-publish `components.java`).
     */
    abstract val javaPublication: Property<Boolean>

    /** Opt-in the ordinary Java publication. */
    fun javaPublication() = javaPublication.set(true)

    /**
     * Per-environment publication settings — artifactId/groupId and
     * adapter-specific options accumulate here; adapters read them at
     * `afterEvaluate`/execution.
     */
    fun environment(name: String, action: Action<EnvironmentPublication>) {
        environments.getOrPut(name) { EnvironmentPublication(name) }
            .also { action.execute(it) }
    }

    /** Per-environment publication settings, keyed by environment id. */
    val environments =
        linkedMapOf<String, EnvironmentPublication>()

    /** Groovy DSL overload. */
    fun environment(
        name: String,
        @DelegatesTo(EnvironmentPublication::class) action: groovy.lang.Closure<*>
    ) {
        environment(name, Action { action.delegate = it; action.call() })
    }

    /**
     * Publication settings for one environment — coordinates plus the
     * publishable-variant selection (dev, production, data files).
     */
    class EnvironmentPublication(val name: String) {
        var groupId: String? = null
        var artifactId: String? = null
        var version: String? = null

        /** Publish the environment's dev (mapped) jar as a variant. */
        var publishDev: Boolean = true

        /** Publish the environment's production (remapped) jar. */
        var publishProduction: Boolean = true

        /** Publish mod-support data files (AW/AT/II/mapping exports). */
        var publishDataFiles: Boolean = true
    }

}
