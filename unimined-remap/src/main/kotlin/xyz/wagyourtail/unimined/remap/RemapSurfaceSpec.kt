package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import groovy.lang.MissingMethodException
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import javax.inject.Inject
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * DSL block for one [RemapSurface]'s step pipeline. Steps run in
 * declaration order.
 *
 * Engine plugins extend the DSL with their own verbs via extension
 * functions — e.g. `fun RemapSurfaceSpec.unpick(defs) = step(UnpickSpec(defs))`.
 */
abstract class RemapSurfaceSpec @Inject constructor(
    val surface: RemapSurface,
    private val objects: ObjectFactory
) : ExtensionAware {
    // `extensions` is supplied by Gradle decoration — instances MUST be
    // created via objects.newInstance so the generated storage is the
    // same one Groovy method dispatch consults. Step-type plugins
    // register DSL verbs via RemapExtension.registerDslVerb — each
    // maps to a Closure-valued impl invoked by [methodMissing] (the
    // dispatch point Gradle Groovy reaches after member + named-
    // extension lookups fail; extension-object methods can't be used
    // because `verb {}` always configures the named extension).
    internal val dslVerbObjects = mutableMapOf<String, Closure<*>>()

    /** Groovy DSL dispatch for plugin verbs: `jst { }`, `unpick("defs")`
     *  — invokes the registered verb closure with the call args. */
    fun methodMissing(name: String, args: Any): Any {
        val verb = dslVerbObjects[name]
            ?: throw MissingMethodException(name, javaClass, args as? Array<Any>)
        @Suppress("UNCHECKED_CAST")
        return verb.call(*(args as Array<Any?>))
    }

    /** Declared steps, in declaration order. */
    val steps: MutableList<StepSpec> = mutableListOf()

    /**
     * Steps contributed by transform plugins — appended after the declared
     * (or inherited) pipeline, never replacing it. Validation counts only
     * [steps], so contributed `remap`-style steps must not change the
     * surface's output namespace.
     */
    val contributed: MutableList<StepSpec> = mutableListOf()

    /**
     * Steps contributed by transform plugins that must run *before* the
     * declared pipeline — on the input jar still in its original
     * namespace. Loader patch pipelines use this for binary patching:
     * forge userdev/FG2 binpatches index official-named classes, so the
     * patch must precede the namespace remap.
     */
    val contributedPre: MutableList<StepSpec> = mutableListOf()

    /** Artifact scope per contributed step — absent means
     *  [StepScope.ALL_ARTIFACTS]. */
    val contributedScopes: MutableMap<StepSpec, StepScope> = mutableMapOf()

    /**
     * Declare the namespace-changing remap step. At most one per surface —
     * it defines the surface's output namespace.
     *
     * @param engine engine impl name, or `null` for capability selection
     */
    @JvmOverloads
    fun remap(
        engine: String? = null,
        action: Action<RemapStepSpec> = Action {}
    ): RemapStepSpec {
        val spec = RemapStepSpec(engine)
        action.execute(spec)
        steps.add(spec)
        return spec
    }

    /** Groovy DSL counterpart of [remap]. */
    fun remap(
        @DelegatesTo(target = "remapSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): RemapStepSpec {
        val spec = RemapStepSpec()
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
        steps.add(spec)
        return spec
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
