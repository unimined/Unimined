package xyz.wagyourtail.unimined.remap

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelectionException
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.remap.spi.*
import java.util.*
import kotlin.reflect.KClass

/**
 * Registry of [RemapStepType] implementations supplied by engine plugins.
 *
 * Engines self-register via `META-INF/services/xyz.wagyourtail.unimined.remap.spi.RemapStepType`
 * — loaded automatically at construction — and may additionally call
 * [register] programmatically. The host resolves each declared DSL step to
 * exactly one impl via the shared selection machinery — grouped by
 * [RemapStepType.specType], so selection + override + ambiguity errors run
 * per step type.
 *
 * Because construction re-discovers impls from the classpath, a fresh
 * registry at execution time sees the same impls as the DSL-time registry —
 * engines never need to be serialized into task state.
 */
class RemapStepRegistry {

    // selectors are partitioned by (spec type, surface): one engine may
    // ship several impls of the same DSL verb on different surfaces
    // (e.g. uml `remap` on JAR vs SOURCES), so selection runs against
    // only the impls that support the surface being resolved
    private val selectors =
        mutableMapOf<Pair<KClass<out StepSpec>, RemapSurface>, SpiSelector<RemapStepType<*>, RemapSpec>>()

    init {
        // ServiceLoader.load() with no explicit loader uses the *thread
        // context* classloader — under transform/worker isolation that
        // loader sees nothing. The SPI class's own loader is the plugin
        // classpath; TCCL is kept as a fallback for hosts that swap it.
        val found = ServiceLoader.load(
            RemapStepType::class.java,
            RemapStepType::class.java.classLoader
        ).toList() + ServiceLoader.load(
            RemapStepType::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        // same impl can arrive via both loaders — dedupe by class, not
        // engine name (one engine ships several steps with its own name)
        found.distinctBy { it::class.qualifiedName }.forEach {
            @Suppress("UNCHECKED_CAST")
            register(it as RemapStepType<StepSpec>)
        }
    }

    /** Register an engine's step implementation. */
    fun <S : StepSpec> register(type: RemapStepType<S>) {
        for (surface in type.surfaces) {
            selectorFor(type.specType to surface).register(type)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun selectorFor(key: Pair<KClass<out StepSpec>, RemapSurface>) =
        selectors.getOrPut(key) {
            spiSelector<RemapStepType<*>, RemapSpec>(
                "remap step '${key.first.simpleName}' on ${key.second}"
            )
        }

    /**
     * Resolve the impl for a declared step on [surface].
     *
     * @throws SpiSelectionException when no impl is registered for the
     *  spec type, none supports the remap spec, or several do without a
     *  [StepSpec.engine] override
     * @throws IllegalArgumentException when the resolved impl does not
     *  support [surface]
     */
    @Suppress("UNCHECKED_CAST")
    fun <S : StepSpec> resolve(
        spec: S,
        surface: RemapSurface,
        remapSpec: RemapSpec
    ): RemapStepType<S> {
        // assignable match — DSL-created specs may be decorated subclasses.
        // Two-step so a spec registered for OTHER surfaces fails with
        // IllegalArgumentException ("does not support"), reserving
        // SpiSelectionException for "no impl for this spec type".
        val candidates = selectors.entries
            .filter { it.key.first.java.isInstance(spec) }
        if (candidates.isEmpty())
            throw SpiSelectionException(
                "no remap step registered for spec '${spec::class.simpleName}'"
            )
        val selector = candidates
            .firstOrNull { it.key.second == surface }
            ?.value
            ?: throw IllegalArgumentException(
                "remap step '${spec::class.simpleName}' does not support surface $surface"
            )
        return selector.select(remapSpec, spec.engine) as RemapStepType<S>
    }

    /**
     * Resolve [spec] and create its executable [ArtifactStep] in one call.
     */
    fun <S : StepSpec> createStep(
        spec: S,
        surface: RemapSurface,
        remapSpec: RemapSpec,
        ctx: StepContext
    ): ArtifactStep = resolve(spec, surface, remapSpec).create(spec, ctx)

}
