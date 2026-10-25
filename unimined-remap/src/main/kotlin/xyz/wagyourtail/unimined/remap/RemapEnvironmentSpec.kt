package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Remap pipelines for one Minecraft environment/source set.
 */
class RemapEnvironmentSpec internal constructor(
    /** Stable environment/source-set name. */
    val environmentId: String,
    private val projectPath: String,
    private val objects: ObjectFactory
) {

    private val surfaces = mutableMapOf<RemapSurface, RemapSurfaceSpec>()
    private val inheritedEnvironments =
        linkedMapOf<EnvironmentReference, () -> RemapEnvironmentSpec>()

    /** Configure the binary/runtime artifact pipeline. */
    fun jar(action: Action<RemapSurfaceSpec>) = configure(RemapSurface.JAR, action)

    /** Groovy DSL overload for the binary/runtime artifact pipeline. */
    fun jar(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = configure(RemapSurface.JAR, action)

    /** Configure the decompile-input artifact pipeline. */
    fun decompile(action: Action<RemapSurfaceSpec>) = configure(RemapSurface.DECOMPILE, action)

    /** Groovy DSL overload for the decompile-input artifact pipeline. */
    fun decompile(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = configure(RemapSurface.DECOMPILE, action)

    /** Configure the sources-jar pipeline. */
    fun sources(action: Action<RemapSurfaceSpec>) = configure(RemapSurface.SOURCES, action)

    /** Groovy DSL overload for the sources-jar pipeline. */
    fun sources(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = configure(RemapSurface.SOURCES, action)

    /** The declared steps for [surface], creating an empty surface if needed. */
    fun surface(surface: RemapSurface): RemapSurfaceSpec =
        surfaces.getOrPut(surface) { RemapSurfaceSpec(surface, objects) }

    /** Surfaces configured directly on this environment, in declaration order. */
    fun configuredSurfaces(): Map<RemapSurface, RemapSurfaceSpec> = surfaces.toMap()

    /** Steps contributed by transform plugins for [surface] — run after the
     *  declared/inherited pipeline. */
    fun contributedSteps(surface: RemapSurface): List<StepSpec> =
        surfaces[surface]?.contributed.orEmpty()

    /** Contribute a plugin step appended after this surface's declared pipeline. */
    fun contributeStep(surface: RemapSurface, spec: StepSpec) {
        surface(surface).contributed += spec
    }

    /** Validate remap-step cardinality for this environment. */
    fun validate() {
        for (surface in effectiveSurfaceKeys()) {
            val remaps = effectiveSteps(surface).count { it is RemapStepSpec }
            if (remaps > 1) {
                throw GradleException(
                    "remap { sourceSet(\"$environmentId\") { $surface { ... } } }: " +
                        "$remaps `remap` steps declared; at most one is allowed"
                )
            }
        }
    }

    internal fun inheritFrom(
        source: EnvironmentReference,
        environment: () -> RemapEnvironmentSpec
    ) {
        inheritedEnvironments[source] = environment
    }

    internal fun effectiveSteps(
        surface: RemapSurface,
        visiting: MutableSet<String> = mutableSetOf()
    ): List<StepSpec> {
        val own = surfaces[surface]?.steps.orEmpty()
        if (own.isNotEmpty()) return own

        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined remap inheritance involving $key")
        }
        try {
            for (resolver in inheritedEnvironments.values.reversed()) {
                val inherited = resolver().effectiveSteps(surface, visiting)
                if (inherited.isNotEmpty()) return inherited
            }
            return emptyList()
        } finally {
            visiting.remove(key)
        }
    }

    private fun effectiveSurfaceKeys(
        visiting: MutableSet<String> = mutableSetOf()
    ): Set<RemapSurface> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined remap inheritance involving $key")
        }
        try {
            return surfaces.keys + inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectiveSurfaceKeys(visiting)
            }
        } finally {
            visiting.remove(key)
        }
    }

    private fun configure(surface: RemapSurface, action: Action<RemapSurfaceSpec>) {
        action.execute(surface(surface))
    }

    private fun configure(surface: RemapSurface, action: Closure<*>) {
        action.delegate = surface(surface)
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

}
