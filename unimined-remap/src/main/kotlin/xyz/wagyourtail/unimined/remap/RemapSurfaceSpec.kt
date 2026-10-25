package xyz.wagyourtail.unimined.remap

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * DSL block for one [RemapSurface]'s step pipeline. Steps run in
 * declaration order.
 *
 * Engine plugins extend the DSL with their own verbs via extension
 * functions — e.g. `fun RemapSurfaceSpec.unpick(defs) = step(UnpickSpec(defs))`.
 */
class RemapSurfaceSpec(
    val surface: RemapSurface,
    private val objects: ObjectFactory
) {

    /** Declared steps, in declaration order. */
    val steps: MutableList<StepSpec> = mutableListOf()

    /**
     * Declare the namespace-changing remap step. At most one per surface —
     * it defines the surface's output namespace.
     *
     * @param engine engine impl name, or `null` for capability selection
     */
    @JvmOverloads
    fun remap(engine: String? = null) {
        steps.add(RemapStepSpec(engine))
    }

    /**
     * Declare the embedded AW/AT/CT resource-remapping step; [action] may
     * restrict formats or add explicit resource paths.
     */
    @JvmOverloads
    fun remapAccess(
        engine: String? = null,
        action: Action<AccessRemapStepSpec> = Action {}
    ): AccessRemapStepSpec {
        val spec = AccessRemapStepSpec(engine)
        action.execute(spec)
        steps.add(spec)
        return spec
    }

    /** Groovy DSL counterpart of [remapAccess]. */
    fun remapAccess(
        @DelegatesTo(target = "accessSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): AccessRemapStepSpec {
        val spec = AccessRemapStepSpec()
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
        steps.add(spec)
        return spec
    }

    /**
     * Declare a step of an engine-supplied spec type. Prefer the engine's
     * own DSL extension (e.g. `unpick(defs)`) over calling this directly.
     */
    fun <S : StepSpec> step(spec: S) {
        steps.add(spec)
    }

    /**
     * Declare a step of an engine-supplied *managed* spec type — for specs
     * with `Property`/`ConfigurableFileCollection` fields that need
     * `ObjectFactory.newInstance` construction:
     *
     * ```kotlin
     * sources {
     *     step(SourceRemapStepSpec::class.java) {
     *         toolClasspath.from(sourceRemapTool)
     *     }
     * }
     * ```
     */
    @JvmOverloads
    fun <S : StepSpec> step(type: Class<S>, action: Action<S> = Action {}): S {
        val spec = objects.newInstance(type)
        action.execute(spec)
        steps.add(spec)
        return spec
    }

    /**
     * Groovy DSL counterpart of the managed-spec [step] overload.
     * The closure delegates to the concrete spec class supplied by [type].
     */
    fun <S : StepSpec> step(
        @DelegatesTo.Target("stepSpec") type: Class<S>,
        @DelegatesTo(target = "stepSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): S {
        val spec = objects.newInstance(type)
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
        steps.add(spec)
        return spec
    }

}
