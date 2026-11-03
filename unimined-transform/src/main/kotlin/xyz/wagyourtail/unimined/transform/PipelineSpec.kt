package xyz.wagyourtail.unimined.transform

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeRegistry
import javax.inject.Inject

/**
 * Declared transform-pipeline scope for one environment — the
 * `pipeline { }` DSL object. Step types register as named extensions
 * (`extensions.add("binpatches", spec)`): Groovy gets extension
 * dispatch free, Kotlin uses per-type extension fns as shipped sugar.
 *
 * [declaredSteps] preserves *registration* order — `extensions.add`
 * calls happen in build-script order during evaluation, so the index
 * is captured at add-time and never depends on when configure lambdas
 * later execute (config-cache realization order is intentionally not
 * script order). Steps are singletons per type; repeatable data lives
 * in list fields on the spec.
 */
abstract class PipelineSpec @Inject constructor(
    val registry: StepTypeRegistry,
    val objects: ObjectFactory,
    val presets: PipelinePresetRegistry = PipelinePresetRegistry()
) {

    /** Steps in registration (build-script declaration) order. */
    val declaredSteps = mutableListOf<DeclaredStep>()

    /** `select(key)` nodes — branches expand in place at realization. */
    val selectors = mutableListOf<SelectorNode>()

    /** Inline `execute { }` glue actions — run every time, own no
     *  artifacts, carry no markers. */
    val inlineActions = mutableListOf<NamedAction>()

    private val specByTypeId = mutableMapOf<String, Any>()

    /**
     * Declare a step of [typeId], configured by [action]. Idempotent —
     * a second call for a singleton type configures the existing spec.
     */
    fun <T : Any> step(typeId: String, specClass: Class<T>, action: Action<in T>): T {
        val type = registry.resolve(typeId)
            ?: throw IllegalArgumentException(
                "unknown pipeline step '$typeId' (registered: " +
                        registry.all.joinToString { it.id } + ")"
            )
        @Suppress("UNCHECKED_CAST")
        val spec = specByTypeId.getOrPut(type.id) {
            val s = objects.newInstance(specClass)
            declaredSteps += DeclaredStep(type, s, declaredSteps.size)
            s
        } as T
        action.execute(spec)
        return spec
    }

    /** `select(key)` — branches keyed by probe result or declared pin. */
    fun select(key: String, action: Action<in SelectorNode>) {
        val node = SelectorNode(key, declaredSteps.size)
        selectors += node
        action.execute(node)
    }

    /**
     * `era("name")` — expand a named [PipelinePreset]'s canonical
     * step list inline at this declaration site. Presets are
     * ServiceLoader-declared by transform modules; unknown names
     * fail at declaration. Probe-driven selection composes with
     * [select]: `select("era") { branch("userdev") { era("userdev") } }`.
     */
    fun era(name: String) {
        presets.require(name).expand(this)
    }

    /** Inline glue action at declaration order — no artifacts/markers. */
    fun execute(name: String, action: Action<in PipelineExecutionScope>) {
        inlineActions += NamedAction(name, declaredSteps.size, action)
    }

    /**
     * Realize the declared pipeline into an ordered step list:
     * expand matching [selectors] branches, then topo-sort over
     * `requires`/`runsAfter` hints with registration-order fallback.
     */
    fun realize(probeResult: (String) -> String?): List<DeclaredStep> =
        realizeAll(probeResult).filterIsInstance<DeclaredStep>()

    /**
     * Realize the declared pipeline including inline [execute] glue:
     * the step list from [realize] with [NamedAction]s merged back at
     * their declaration position — an action declared after *k* step
     * declarations runs before the resolved step holding
     * `orderIndex == k` (and before any step topo-moved past it).
     * Consumers dispatch on the component type.
     */
    fun realizeAll(probeResult: (String) -> String?): List<OrderedComponent> {
        val steps = declaredSteps.toMutableList()
        for (sel in selectors.sortedBy { it.orderIndex }) {
            val branch = sel.branch(probeResult(sel.key))
            val at = steps.indexOfFirst { it.orderIndex > sel.orderIndex }
                .takeIf { it >= 0 } ?: steps.size
            steps.addAll(at, branch)
        }
        val out = registry.resolveOrder(steps).toMutableList<OrderedComponent>()
        for (a in inlineActions) {
            val at = out.indexOfFirst { it.orderIndex >= a.orderIndex }
                .takeIf { it >= 0 } ?: out.size
            out.add(at, a)
        }
        return out
    }

    /** A declared step — its type, configured spec, registration index. */
    data class DeclaredStep(
        val type: StepType,
        val spec: Any,
        override val orderIndex: Int
    ) : OrderedComponent, StepType by type

    /** `select(key)` node holding named branches of nested step lists. */
    inner class SelectorNode(val key: String, override val orderIndex: Int)
        : OrderedComponent {

        val branches = mutableMapOf<String, MutableList<DeclaredStep>>()

        /** Declare a branch — [pin] must resolve against the probe's
         *  declared key vocabulary at registration. */
        fun branch(pin: String, action: Action<in PipelineSpec>) {
            val nested = objects.newInstance(PipelineSpec::class.java,
                registry, objects, presets)
            action.execute(nested)
            branches[pin] = nested.declaredSteps
        }

        fun branch(result: String?): List<DeclaredStep> =
            branches[result]
                ?: throw IllegalStateException(
                    "select($key): no branch for '$result' " +
                            "(declared: ${branches.keys.joinToString()})"
                )
    }

    /** Inline action — declaration-ordered, no artifacts/markers. */
    data class NamedAction(
        val name: String,
        override val orderIndex: Int,
        val action: Action<in PipelineExecutionScope>
    ) : OrderedComponent

    /** Components ordered by build-script registration index. */
    interface OrderedComponent {
        val orderIndex: Int
    }

    /**
     * Scope an inline `execute {}` body receives at transform-build
     * time — config-time glue, not artifact work. Exposes the env's
     * wiring channels (`env.runMutators`, `env.probeResolver`, jar
     * processors, …) and the bound Minecraft environment
     * (`accessTransformers`, `modSourceNamespaces`, …).
     */
    interface PipelineExecutionScope {
        val environmentId: String
        val project: org.gradle.api.Project
        val env: TransformEnvironmentSpec
        val minecraft: xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment?
    }

}
