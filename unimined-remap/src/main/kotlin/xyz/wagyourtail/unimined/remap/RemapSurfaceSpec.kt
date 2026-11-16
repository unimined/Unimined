package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import xyz.wagyourtail.unimined.api.util.callDelegate
import groovy.lang.MissingMethodException
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import javax.inject.Inject
import xyz.wagyourtail.unimined.api.util.UniminedProblems
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
     *  — invokes the registered verb closure with the call args. 1.x
     *  surface verbs removed in 2.0 emit migration notes; known engine
     *  verbs whose contributing plugin isn't applied name the plugin. */
    fun methodMissing(name: String, args: Any): Any {
        val verb = dslVerbObjects[name]
        if (verb != null) {
            @Suppress("UNCHECKED_CAST")
            return verb.call(*(args as Array<Any?>))
        }
        removed1xVerbs[name]?.let { note ->
            UniminedProblems.fail(
                LOGGER, objects,
                "remap/removed-1x-verb",
                "unimined: remap surface '${surface.name.lowercase()}' — " +
                        "'$name' was removed in Unimined 2.0",
                note
            )
        }
        pluginVerbs[name]?.let { plugin ->
            UniminedProblems.fail(
                LOGGER, objects,
                "remap/verb-plugin-missing",
                "unimined: remap verb '$name' is contributed by the " +
                        "'$plugin' plugin, which is not applied",
                "apply `id \"$plugin\"` to use `$name { }` on remap " +
                        "surfaces"
            )
        }
        throw MissingMethodException(name, javaClass, args as? Array<Any>)
    }

    private companion object {
        val LOGGER = Logging.getLogger(RemapSurfaceSpec::class.java)

        /**
         * 1.x remap-task/surface verbs removed in 2.0 — Groovy calls
         *  hit [methodMissing] and get a migration note instead of a
         *  bare missing-method error (docs/migration-1x-to-2.0.md
         *  §Removed extension points).
         */
        val removed1xVerbs: Map<String, String> = buildMap {
            this["mixinRemap"] =
                "mixin/refmap rewriting moved to the `modArtifacts { }` " +
                        "step — `mixinRemap { off(); disableRefmap(...) }` " +
                        "became `modArtifacts { off() / disableRefmap(...) }`"
            val namespaces =
                "namespaces are not declared on the surface in 2.0 — " +
                        "they come from the environment's " +
                        "source/dev/prod namespace declarations and the " +
                        "`remap { }` step"
            this["devNamespace"] = namespaces
            this["prodNamespace"] = namespaces
            this["devFallbackNamespace"] = namespaces
            val access = "access-file remapping is the `remapAccess { }` " +
                    "step in 2.0"
            this["remapAccessWidener"] = access
            this["remapAccessTransformer"] = access
            this["remapATToLegacy"] = access
            val engine = "tiny-remapper builder callbacks were removed — " +
                    "engine knobs live on the `remap { }`/engine step " +
                    "specs (e.g. `jsr305ToJetbrains`) in 2.0"
            this["config"] = engine
            this["tinyRemapperConf"] = engine
            this["remapper"] = engine
            this["addResourceRemapper"] = engine
            this["addExtension"] = engine
            this["replaceJSRWithJetbrains"] =
                "`jsr305ToJetbrains` on the `remap { }` step spec " +
                        "(default on) in 2.0"
            this["allowImplicitWildcards"] =
                "removed in Unimined 2.0 — no equivalent; open an issue " +
                        "if a workflow depended on it"
        }

        /**
         * Verbs contributed by optional engine/adapter plugins — when
         * the plugin isn't applied the verb never registers, so name
         * the plugin instead of a bare missing-method error.
         */
        val pluginVerbs: Map<String, String> = mapOf(
            "unpick" to "xyz.wagyourtail.unimined.remapper.uml",
            "nester" to "xyz.wagyourtail.unimined.remapper.uml",
            "raven" to "xyz.wagyourtail.unimined.remapper.uml",
            "sparrow" to "xyz.wagyourtail.unimined.remapper.uml",
            "condor" to "xyz.wagyourtail.unimined.remapper.uml",
            "preen" to "xyz.wagyourtail.unimined.remapper.uml",
            "jst" to "xyz.wagyourtail.unimined.remapper.jst",
            "sourceRemap" to "xyz.wagyourtail.unimined.remapper.source",
            "applyClassTweaker" to "xyz.wagyourtail.unimined.remapper.access",
            "expectPlatform" to "xyz.wagyourtail.unimined.adapter-expectplatform",
            "downgrade" to "xyz.wagyourtail.unimined.jvmdg",
        )
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
        action.callDelegate()
        steps.add(spec)
        return spec
    }

    /**
     * Groovy DSL — `remap("tiny") { ... }`: engine pin plus
     * configuration. Without this overload the trailing closure
     * SAM-coerces to `Action` and delegates to the build script.
     */
    fun remap(
        engine: String?,
        @DelegatesTo(target = "remapSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): RemapStepSpec {
        val spec = RemapStepSpec(engine)
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
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
        action.callDelegate()
        steps.add(spec)
        return spec
    }

    /** Groovy DSL — `remapAccess("tiny") { ... }`: engine pin plus
     *  configuration. */
    fun remapAccess(
        engine: String?,
        @DelegatesTo(target = "accessSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): AccessRemapStepSpec {
        val spec = AccessRemapStepSpec(engine)
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
        steps.add(spec)
        return spec
    }

    /** Declare the mod-artifact post-remap step (kotlin metadata,
     *  mixin configs + refmaps, JarModAgent refmaps) explicitly. JAR
     *  pipelines containing a `remap` step get it implicitly; use this
     *  to pin an engine or a non-default ordering. */
    @JvmOverloads
    fun modArtifacts(
        engine: String? = null,
        action: Action<ModArtifactsStepSpec> = Action {}
    ): ModArtifactsStepSpec {
        val spec = ModArtifactsStepSpec(engine)
        action.execute(spec)
        steps.add(spec)
        return spec
    }

    /** Groovy DSL counterpart of [modArtifacts]. */
    fun modArtifacts(
        @DelegatesTo(target = "modArtifactsSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): ModArtifactsStepSpec {
        val spec = ModArtifactsStepSpec()
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
        steps.add(spec)
        return spec
    }

    /** Groovy DSL — `modArtifacts("uml") { ... }`: engine pin plus
     *  configuration. */
    fun modArtifacts(
        engine: String?,
        @DelegatesTo(target = "modArtifactsSpec", strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ): ModArtifactsStepSpec {
        val spec = ModArtifactsStepSpec(engine)
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
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
        action.callDelegate()
        steps.add(spec)
        return spec
    }

}
