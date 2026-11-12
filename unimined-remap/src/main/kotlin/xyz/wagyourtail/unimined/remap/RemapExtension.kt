package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.remap.spi.EmitArtifactStepSpec
import xyz.wagyourtail.unimined.remap.task.RemapArtifactTask
import xyz.wagyourtail.unimined.remap.internal.ModMarkerScan
import xyz.wagyourtail.unimined.remap.internal.RemappedViewWiring
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.remap.spi.StripNestedJarsStepSpec
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform
import javax.inject.Inject

/**
 * The `remap { }` DSL: ordered step pipelines per [RemapSurface], plus
 * remapped views over dependency configurations.
 *
 * ```kotlin
 * remap {
 *     jar {
 *         remap("tiny")          // the namespace-changing step
 *         unpick(unpickDefs)     // e.g. a UML step, post-remap
 *     }
 *     sources {
 *         remap("uml")
 *     }
 * }
 * ```
 */
abstract class RemapExtension @Inject constructor(
    internal val project: Project,
    val objects: ObjectFactory,
    private val providers: ProviderFactory
) {

    /** Engine-supplied step impls; populated by engine plugins. */
    val registry = RemapStepRegistry()

    /** Remapped-dependency-view wiring — [RemappedViewWiring] owns
     *  view creation + artifact-transform registration; the verbs
     *  delegate after populating the spec. */
    internal val viewWiring = RemappedViewWiring(this)

    /**
     * Post-remap validation level for production jars — packaging
     * footguns compile fine and only fail at runtime: dangling
     * access-widener/mixin/nested-jar/icon paths, missing
     * entrypoint/main classes, wrong remapped-namespace marker.
     * Defaults to [FootgunCheckLevel.ERROR]; `remap { footgunChecks = WARN }`
     * downgrades to warnings, `OFF` disables.
     */
    abstract val footgunChecks: org.gradle.api.provider.Property<FootgunCheckLevel>

    private val environments = mutableMapOf<String, RemapEnvironmentSpec>()

    /**
     * Plugin-registered DSL verbs for [RemapSurfaceSpec] — engine plugins
     * register a factory per verb during `apply` so Groovy reaches their
     * step DSL through [ExtensionAware] dispatch (`remap { jst {} }`),
     * where the Kotlin extension functions are invisible.
     */
    private val dslVerbs = mutableMapOf<String, (RemapSurfaceSpec) -> Any>()

    /** Register a Groovy-dispatchable DSL verb object for remap surfaces. */
    fun registerDslVerb(name: String, factory: (RemapSurfaceSpec) -> Any) {
        dslVerbs[name] = factory
        // verbs registered after spec creation land on existing specs too
        environments.values.forEach { env ->
            env.dslVerbs = dslVerbs.toMap()
            env.configuredSurfaces().values.forEach { spec ->
                spec.dslVerbObjects.getOrPut(name) {
                    val verb = factory(spec)
                    check(verb is Closure<*>) {
                        "remap DSL verb '$name' must be a Closure (use stepVerb)"
                    }
                    verb
                }
            }
        }
    }

    init {
        footgunChecks.convention(FootgunCheckLevel.ERROR)
        EnvironmentCopyRegistry.registerHandler(
            project,
            RemapEnvironmentCopyHandler(project)
        )
        sourceSet("main")
    }

    /** Get or create the remap pipelines for [environmentId]. */
    fun sourceSet(environmentId: String): RemapEnvironmentSpec =
        environments.getOrPut(environmentId) {
            RemapEnvironmentSpec(environmentId, project.path, objects).also {
                it.dslVerbs = dslVerbs.toMap()
            }
        }

    /** Configure remap pipelines for one Minecraft environment. */
    fun sourceSet(
        environmentId: String,
        action: Action<RemapEnvironmentSpec>
    ): RemapEnvironmentSpec = sourceSet(environmentId).also(action::execute)

    /** Groovy DSL overload for environment-scoped remap pipelines. */
    fun sourceSet(
        environmentId: String,
        @DelegatesTo(value = RemapEnvironmentSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): RemapEnvironmentSpec = sourceSet(environmentId).also { environment ->
        action.delegate = environment
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** Configure the binary/runtime artifact pipeline. */
    fun jar(action: Action<RemapSurfaceSpec>) = sourceSet("main").jar(action)

    /** Groovy DSL overload for the binary/runtime artifact pipeline. */
    fun jar(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet("main").jar(action)

    /** Configure a named environment's binary/runtime artifact pipeline. */
    fun jar(environmentId: String, action: Action<RemapSurfaceSpec>) =
        sourceSet(environmentId).jar(action)

    /** Groovy DSL overload for a named environment's JAR pipeline. */
    fun jar(
        environmentId: String,
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet(environmentId).jar(action)

    /** Configure the decompile-input (fork of the runtime jar) pipeline. */
    fun decompile(action: Action<RemapSurfaceSpec>) = sourceSet("main").decompile(action)

    /** Groovy DSL overload for the decompile-input pipeline. */
    fun decompile(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet("main").decompile(action)

    /** Configure a named environment's decompile-input pipeline. */
    fun decompile(environmentId: String, action: Action<RemapSurfaceSpec>) =
        sourceSet(environmentId).decompile(action)

    /** Groovy DSL overload for a named environment's decompile pipeline. */
    fun decompile(
        environmentId: String,
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet(environmentId).decompile(action)

    /** Configure the sources-jar pipeline. */
    fun sources(action: Action<RemapSurfaceSpec>) = sourceSet("main").sources(action)

    /** Groovy DSL overload for the sources-jar pipeline. */
    fun sources(
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet("main").sources(action)

    /** Configure a named environment's sources pipeline. */
    fun sources(environmentId: String, action: Action<RemapSurfaceSpec>) =
        sourceSet(environmentId).sources(action)

    /** Groovy DSL overload for a named environment's sources pipeline. */
    fun sources(
        environmentId: String,
        @DelegatesTo(value = RemapSurfaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet(environmentId).sources(action)

    /** The spec for [surface], creating it empty if unconfigured. */
    fun surface(surface: RemapSurface): RemapSurfaceSpec =
        sourceSet("main").surface(surface)

    /** The spec for [surface] in [environmentId]. */
    fun surface(environmentId: String, surface: RemapSurface): RemapSurfaceSpec =
        sourceSet(environmentId).surface(surface)

    /** Configured surfaces in declaration order. */
    fun configuredSurfaces(): Map<RemapSurface, RemapSurfaceSpec> =
        sourceSet("main").configuredSurfaces()

    /** Configured surfaces for a named environment. */
    fun configuredSurfaces(environmentId: String): Map<RemapSurface, RemapSurfaceSpec> =
        sourceSet(environmentId).configuredSurfaces()

    /**
     * Lazily-providable view of a surface's declared pipeline — wire into a
     * task's `stepSpecs` property to bind the DSL:
     *
     * ```kotlin
     * tasks.register<RemapJarTask>("remapJar") {
     *     stepSpecs.set(remap.stepSpecs(RemapSurface.JAR))
     * }
     * ```
     *
     * Runs [validate] when queried, so DSL errors surface with the task
     * that consumed the pipeline.
     */
    fun stepSpecs(surface: RemapSurface): Provider<List<StepSpec>> =
        stepSpecs("main", surface)

    /** Environment-scoped view of a declared remap pipeline. */
    fun stepSpecs(environmentId: String, surface: RemapSurface): Provider<List<StepSpec>> =
        providers.provider {
            val environment = sourceSet(environmentId)
            environment.validate()
            environment.effectiveSteps(surface)
        }

    /**
     * The executable pipeline for a surface: transform-contributed `pre`
     * steps (original-namespace input — loader binary patching), the
     * declared (or inherited) steps or an implicit `remap` core when a
     * JAR surface was left unconfigured, then post steps. Hosts
     * feeding unimined-managed artifacts (dev jars, environment mod
     * buckets) use this so empty pipelines still namespace-remap.
     */
    fun pipelineSteps(environmentId: String, surface: RemapSurface): Provider<List<StepSpec>> =
        pipelineSteps(environmentId, surface, includeEnvironmentArtifactSteps = true)

    /**
     * [pipelineSteps] for remapped dependency views: contributions scoped
     * [StepScope.ENVIRONMENT_ARTIFACT] (loader binary patching, class
     * injection — they only apply to the environment's own jar) are
     * excluded; shared contributions (access transforms, access fixes)
     * still apply.
     */
    fun dependencyPipelineSteps(
        environmentId: String,
        surface: RemapSurface
    ): Provider<List<StepSpec>> {
        val steps = pipelineSteps(
            environmentId, surface, includeEnvironmentArtifactSteps = false)
        return if (surface != RemapSurface.JAR) steps
        else steps.map {
            // dep jars' JiJ declarations don't survive the dev remap —
            // drop them from dep `*.mod.json` metadata (1.x
            // innerJarRemapper parity); the prod pipeline is untouched
            it + listOf<StepSpec>(StripNestedJarsStepSpec())
        }
    }

    private fun pipelineSteps(
        environmentId: String,
        surface: RemapSurface,
        includeEnvironmentArtifactSteps: Boolean,
        includeModArtifactsStep: Boolean = true
    ): Provider<List<StepSpec>> =
        providers.provider {
            val environment = sourceSet(environmentId)
            fun <T> named(what: String, block: () -> T): T = try { block() }
                catch (e: Exception) {
                    throw GradleException(
                        "unimined: pipelineSteps($environmentId, $surface) " +
                        "failed resolving $what: ${e.message}", e)
                }
            named("validate()") { environment.validate() }
            val declared = named("effectiveSteps") {
                environment.effectiveSteps(surface) }
            val core = if (declared.isEmpty() &&
                (surface == RemapSurface.JAR || surface == RemapSurface.SOURCES)
            ) {
                // SOURCES resolves to the uml source-remap step (remap +
                // javadoc-from-mappings) when remapper-uml is applied
                listOf<StepSpec>(RemapStepSpec())
            } else {
                declared
            }
            val scope = if (includeEnvironmentArtifactSteps) null
            else StepScope.ALL_ARTIFACTS
            named("contributedPreSteps") {
                environment.contributedPreSteps(surface, scope)
            } + (if (includeEnvironmentArtifactSteps && surface == RemapSurface.JAR) {
                // emit the "prod jar" — the artifact as of the end of
                // the original-namespace contributions, before the
                // namespace remap and post/enhance steps — so consumers
                // (envArtifactBindings["mc:prod"], prod runs, tooling)
                // get a real file. The emit is a fusible tap inside the
                // group's traversal; dropped automatically on mod-jar
                // and remapJar pipelines by DevArtifactOnlyStepSpec.
                listOf<StepSpec>(
                    EmitArtifactStepSpec(
                        UniminedCache.emitted(project)
                            .resolve("$environmentId-prod.jar").toString()
                    )
                )
            } else emptyList()) + core + (if (includeModArtifactsStep)
                withModArtifactsStep(environment, core, surface)
            else emptyList()) +
            named("contributedSteps") {
                environment.contributedSteps(surface, scope)
            }
        }

    /** [pipelineSteps] for `main`. */
    fun pipelineSteps(surface: RemapSurface): Provider<List<StepSpec>> =
        pipelineSteps("main", surface)

    /**
     * The convention for user-declared remapped views: the declared (or
     * inherited) steps plus shared transform contributions, with no
     * implicit `remap` core — an unconfigured surface is a documented
     * pass-through. Environment-artifact-scoped contributions (loader
     * patch steps) are excluded — views resolve dependency jars.
     */
    private fun viewSteps(environmentId: String, surface: RemapSurface): Provider<List<StepSpec>> =
        providers.provider {
            val environment = sourceSet(environmentId)
            environment.validate()
            val declared = environment.effectiveSteps(surface)
            declared + withModArtifactsStep(environment, declared, surface) +
                    environment.contributedSteps(surface, StepScope.ALL_ARTIFACTS)
        }

    /**
     * Appends the mod-artifact post-remap pass (kotlin metadata, mixin
     * configs + refmaps, JMA refmaps) when [steps] remaps namespaces on
     * the JAR surface — engine-agnostic, so a `remap("tiny")` override
     * or remapped dependency view gets the same treatment. No-op when
     * the user declared the step explicitly or the pipeline carries no
     * namespace remap (identity views stay untouched).
     */
    private fun withModArtifactsStep(
        environment: RemapEnvironmentSpec,
        steps: List<StepSpec>, surface: RemapSurface
    ): List<StepSpec> =
        if (surface == RemapSurface.JAR &&
            steps.any { it is RemapStepSpec } &&
            steps.none { it is ModArtifactsStepSpec }
        ) listOf(ModArtifactsStepSpec().also { spec ->
            environment.modArtifactsConventions.forEach { it.execute(spec) }
        })
        else emptyList()

    /**
     * Create a resolvable [Configuration] that resolves [source]'s
     * dependencies remapped through the JAR-surface pipeline.
     *
     * The view `extendsFrom` the source configuration and requests a
     * per-view `artifactType` token (`unimined-remapped-<target>[<disc>]`).
     * `artifactType` is the attribute that triggers artifact transforms —
     * a registered [RemapDependencyTransform] produces the remapped
     * artifact at resolution time — lazily, cacheably, and during IDE
     * sync. (Custom attributes can't drive transforms: an absent producer
     * attribute wildcard-satisfies the request, and a compatibility rule
     * marking the mismatch "compatible" suppresses the transform too.)
     *
     * ```kotlin
     * val remappedDeps = remap.remappedView("modImplementation") {
     *     sourceNamespace.set("official")
     *     targetNamespace.set("named")
     *     mappingFile.set(produceMappings.flatMap { it.output })
     *     classpath.from(configurations.compileClasspath)
     * }
     * configurations.named("compileClasspath") { extendsFrom(remappedDeps) }
     * ```
     */
    fun remappedView(
        source: String,
        action: Action<RemappedViewSpec>
    ): Configuration =
        remappedViewFor("main", source, "${source}Remapped", action)

    /** Groovy DSL overload using the default view name. */
    fun remappedView(
        source: String,
        @DelegatesTo(value = RemappedViewSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): Configuration =
        remappedViewFor("main", source, "${source}Remapped", action)

    /** Configure a remapped dependency view with an explicit name. */
    fun remappedView(
        source: String,
        name: String,
        action: Action<RemappedViewSpec>
    ): Configuration = remappedViewFor("main", source, name, action)

    /** Create a remapped dependency view for a named environment. */
    fun remappedViewFor(
        environmentId: String,
        source: String,
        action: Action<RemappedViewSpec>
    ): Configuration = remappedViewFor(
        environmentId, source, viewWiring.defaultViewName(environmentId, source), action
    )

    /** Groovy DSL overload for the default-named environment view. */
    fun remappedViewFor(
        environmentId: String,
        source: String,
        @DelegatesTo(value = RemappedViewSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): Configuration = remappedViewFor(
        environmentId, source, viewWiring.defaultViewName(environmentId, source), action
    )

    /** Create a named remapped dependency view for a named environment. */
    fun remappedViewFor(
        environmentId: String,
        source: String,
        name: String,
        action: Action<RemappedViewSpec>
    ): Configuration {
        val spec = objects.newInstance(RemappedViewSpec::class.java)
        spec.discriminator.convention("")
        spec.stepSpecs.convention(viewSteps(environmentId, RemapSurface.JAR))
        // defaults + transform-plugin contributions — evaluated lazily
        // so plugins may register markers after view creation
        spec.modMarkers.convention(project.provider {
            ModMarkerScan.DEFAULT_MARKERS +
                    sourceSet(environmentId).modMarkerContributions
        })
        spec.onlyRemapMarkedJars.convention(true)
        action.execute(spec)
        return viewWiring.createRemappedView(environmentId, source, name, spec)
    }

    /**
     * Create a set of remapped views for one environment. Use this when a
     * source set consumes mods from more than one input namespace.
     */
    fun remappedViews(
        baseName: String,
        action: Action<RemappedViewsSpec>
    ): RemappedViewSet = remappedViewsFor("main", baseName, action)

    /** Groovy DSL overload for a multi-namespace remapped view set. */
    fun remappedViews(
        baseName: String,
        @DelegatesTo(value = RemappedViewsSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): RemappedViewSet = remappedViewsFor("main", baseName, action)

    /** Create a multi-namespace remapped view set for [environmentId]. */
    fun remappedViewsFor(
        environmentId: String,
        baseName: String,
        action: Action<RemappedViewsSpec>
    ): RemappedViewSet {
        val spec = objects.newInstance(RemappedViewsSpec::class.java)
        spec.baseName = baseName
        spec.discriminator.convention("")
        spec.stepSpecs.convention(viewSteps(environmentId, RemapSurface.JAR))
        spec.modMarkers.convention(project.provider {
            ModMarkerScan.DEFAULT_MARKERS +
                    sourceSet(environmentId).modMarkerContributions
        })
        spec.onlyRemapMarkedJars.convention(true)
        action.execute(spec)

        require(spec.targetNamespace.isPresent) {
            "remappedViews('$baseName') must set targetNamespace"
        }
        require(spec.namespaces.isNotEmpty()) {
            "remappedViews('$baseName') must declare at least one source namespace"
        }

        return viewWiring.buildViewSet(environmentId, baseName, spec)
    }

    /** Groovy DSL overload for an environment-scoped remapped view set. */
    fun remappedViewsFor(
        environmentId: String,
        baseName: String,
        @DelegatesTo(value = RemappedViewsSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): RemappedViewSet = remappedViewsFor(environmentId, baseName, Action { spec ->
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    })

    /** Groovy DSL overload for a named remapped dependency view. */
    fun remappedViewFor(
        environmentId: String,
        source: String,
        name: String,
        @DelegatesTo(value = RemappedViewSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): Configuration = remappedViewFor(environmentId, source, name, Action { spec ->
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    })

    /**
     * Register a standalone one-off artifact remap — the non-Minecraft
     * entry point: any jar in, remapped jar out.
     *
     * The mapping file may be a SerializedMappingData bundle, a legacy
     * MappingBundle, or any UML-recognized format (tiny, umf, srg, tsrg,
     * proguard, mcp, ...) autodetected by content at execution time.
     * When `from`/`to` are unset the namespaces are autodetected: the
     * column whose class names best match the input jar becomes the
     * source, and the single remaining column becomes the target.
     *
     * ```kotlin
     * remap {
     *     artifact("baritone") {
     *         input.set(layout.projectDirectory.file("baritone.jar"))
     *         mappings.set(layout.projectDirectory.file("baritone.mapping.txt"))
     *         from.set("obf")
     *         to.set("deobf")
     *     }
     * }
     * // -> :remapBaritoneArtifact
     * ```
     */
    fun artifact(name: String, action: Action<RemapArtifactSpec>): TaskProvider<RemapArtifactTask> {
        val spec = objects.newInstance(RemapArtifactSpec::class.java)
        spec.output.convention(
            project.layout.buildDirectory.file("remapped/$name-remapped.jar")
        )
        action.execute(spec)
        val taskName = "remap${name.replaceFirstChar(Char::uppercase)}Artifact"
        return project.tasks.register(taskName, RemapArtifactTask::class.java) { t ->
            t.group = "unimined"
            t.description = "Remaps the '$name' artifact between namespaces"
            t.inputArtifact.set(spec.input)
            t.outputArtifact.set(spec.output)
            t.surface.set(RemapSurface.JAR)
            t.mappingFile.set(spec.mappings)
            t.sourceNamespace.set(spec.from)
            t.targetNamespace.set(spec.to)
            t.remapClasspath.from(spec.classpath)
            t.stepSpecs.set(artifactSteps())
            t.footgunChecks.set(footgunChecks)
        }
    }

    /**
     * Step pipeline for standalone artifacts: the environment's declared
     * JAR steps (or the implicit remap core) plus `ALL_ARTIFACTS`-scoped
     * contributions — but never `ModArtifactsStepSpec`, whose impl only
     * exists in remapper-uml and only makes sense for environment jars.
     */
    private fun artifactSteps(): Provider<List<StepSpec>> =
        pipelineSteps(
            "main", RemapSurface.JAR,
            includeEnvironmentArtifactSteps = false,
            includeModArtifactsStep = false
        )

    /** Standalone artifact remap using the default `remapArtifact` task. */
    fun artifact(action: Action<RemapArtifactSpec>): TaskProvider<RemapArtifactTask> =
        artifact("Artifact", action)

    /** Groovy DSL overload for a named standalone artifact remap. */
    fun artifact(
        name: String,
        @DelegatesTo(value = RemapArtifactSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): TaskProvider<RemapArtifactTask> = artifact(name, Action { spec ->
        action.delegate = spec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    })

    /** Groovy DSL overload for the default-named artifact remap. */
    fun artifact(
        @DelegatesTo(value = RemapArtifactSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): TaskProvider<RemapArtifactTask> = artifact("Artifact", action)

    /**
     * DSL-time validation.
     *
     * At most one `remap` step per surface — it defines the output
     * namespace; multi-hop remaps are composed in the mapping tree, not by
     * chaining remap steps. A surface may have *zero* remap steps (e.g.
     * `decompile { preen(); condor() }` never changes namespace).
     */
    fun validate() {
        environments.values.forEach(RemapEnvironmentSpec::validate)
    }

}
