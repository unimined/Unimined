package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
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

    /** Plugin-registered DSL verb factories — populated by
     *  [RemapExtension.registerDslVerb] before spec creation. */
    var dslVerbs: Map<String, (RemapSurfaceSpec) -> Any> = emptyMap()
        internal set
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
        surfaces.getOrPut(surface) {
            // newInstance (not a plain ctor) — decoration wires the
            // Groovy metaclass so extension-verb dispatch works
            objects.newInstance(RemapSurfaceSpec::class.java, surface, objects).also { spec ->
                dslVerbs.forEach { (name, factory) ->
                    val verb = factory(spec)
                    check(verb is Closure<*>) {
                        "remap DSL verb '$name' must be a Closure (use stepVerb)"
                    }
                    spec.dslVerbObjects[name] = verb
                }
            }
        }

    /** Surfaces configured directly on this environment, in declaration order. */
    fun configuredSurfaces(): Map<RemapSurface, RemapSurfaceSpec> = surfaces.toMap()

    /** Steps contributed by transform plugins for [surface] — run after the
     *  declared/inherited pipeline. [scope] filters by artifact scope;
     *  `null` returns all contributions. */
    fun contributedSteps(surface: RemapSurface, scope: StepScope? = null): List<StepSpec> =
        surfaces[surface]?.let { spec ->
            spec.contributed.filter {
                scope == null ||
                        (spec.contributedScopes[it] ?: StepScope.ALL_ARTIFACTS) == scope
            }
        }.orEmpty()

    /** Steps contributed by transform plugins for [surface] — run *before*
     *  the declared pipeline, on the original-namespace input.
     *  [scope] filters by artifact scope; `null` returns all. */
    fun contributedPreSteps(surface: RemapSurface, scope: StepScope? = null): List<StepSpec> =
        surfaces[surface]?.let { spec ->
            spec.contributedPre.filter {
                scope == null ||
                        (spec.contributedScopes[it] ?: StepScope.ALL_ARTIFACTS) == scope
            }
        }.orEmpty()

    /** Contribute a plugin step appended after this surface's declared pipeline. */
    @JvmOverloads
    fun contributeStep(
        surface: RemapSurface,
        spec: StepSpec,
        scope: StepScope = StepScope.ALL_ARTIFACTS
    ) {
        surface(surface).let {
            it.contributed += spec
            it.contributedScopes[spec] = scope
        }
    }

    /** Contribute a plugin step that runs before this surface's declared
     *  pipeline — the input is still in its original namespace. */
    @JvmOverloads
    fun contributePreStep(
        surface: RemapSurface,
        spec: StepSpec,
        scope: StepScope = StepScope.ALL_ARTIFACTS
    ) {
        surface(surface).let {
            it.contributedPre += spec
            it.contributedScopes[spec] = scope
        }
    }

    private val _accessModelProviders = mutableListOf<Provider<AccessModel>>()

    /**
     * [AccessModel] contributions registered by loader transforms —
     * merged and fed to this environment's remapped views (and
     * production remap tasks) as the remap-analysis overlay.
     */
    val accessModelProviders: List<Provider<AccessModel>>
        get() = _accessModelProviders

    /**
     * Contribute a lazy access-model source — evaluated at pipeline
     * execution so it may resolve mod dependency configurations.
     */
    fun contributeAccessModel(provider: Provider<AccessModel>) {
        _accessModelProviders += provider
    }

    private val _modMarkerContributions = mutableListOf<String>()

    /**
     * Extra mod markers contributed by transform plugins — appended
     * to `ModMarkerScan.DEFAULT_MARKERS` when deciding whether a
     * mod jar needs remapping in marker-filtered views. Formats:
     * `file:<path>`, `suffix:<suffix>`, `manifest:<attr>`,
     * `refs:<string>`.
     */
    val modMarkerContributions: List<String>
        get() = _modMarkerContributions

    /**
     * Contribute a mod-marker pattern — jars matching at least one
     * marker get remapped in `onlyRemapMarkedJars` views.
     */
    fun contributeModMarker(marker: String) {
        _modMarkerContributions += marker
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
