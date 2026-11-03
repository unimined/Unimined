package xyz.wagyourtail.unimined.runs

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * The `runs { }` DSL: a container of named run configurations. Loader
 * plugins and environment binding contribute the defaults — `client` and
 * `server` entries for every Minecraft environment — and users can add
 * custom variants (`run` tasks materialize as `run<Name>`).
 */
abstract class RunsExtension @Inject constructor(objects: ObjectFactory) {

    /** All declared run configurations. */
    val runs: NamedDomainObjectContainer<RunConfig> =
        objects.domainObjectContainer(RunConfig::class.java)

    /** Configure a run by name — creates it if absent. */
    fun named(name: String, action: Action<RunConfig>) {
        runs.maybeCreate(name).let(action::execute)
    }

    /** Declare a custom run configuration. */
    fun create(name: String, action: Action<RunConfig>) {
        action.execute(runs.create(name))
    }

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
        action.call()
    }

}
