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
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
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
    private val project: Project,
    val objects: ObjectFactory,
    private val providers: ProviderFactory
) {

    /** Engine-supplied step impls; populated by engine plugins. */
    val registry = RemapStepRegistry()

    private val environments = mutableMapOf<String, RemapEnvironmentSpec>()

    init {
        EnvironmentCopyRegistry.registerHandler(
            project,
            RemapEnvironmentCopyHandler(project)
        )
        sourceSet("main")
    }

    /** Get or create the remap pipelines for [environmentId]. */
    fun sourceSet(environmentId: String): RemapEnvironmentSpec =
        environments.getOrPut(environmentId) {
            RemapEnvironmentSpec(environmentId, project.path, objects)
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
        environmentId, source, defaultViewName(environmentId, source), action
    )

    /** Groovy DSL overload for the default-named environment view. */
    fun remappedViewFor(
        environmentId: String,
        source: String,
        @DelegatesTo(value = RemappedViewSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): Configuration = remappedViewFor(
        environmentId, source, defaultViewName(environmentId, source), action
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
        spec.stepSpecs.convention(stepSpecs(environmentId, RemapSurface.JAR))
        action.execute(spec)
        return createRemappedView(environmentId, source, name, spec)
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
        spec.stepSpecs.convention(stepSpecs(environmentId, RemapSurface.JAR))
        action.execute(spec)

        require(spec.targetNamespace.isPresent) {
            "remappedViews('$baseName') must set targetNamespace"
        }
        require(spec.namespaces.isNotEmpty()) {
            "remappedViews('$baseName') must declare at least one source namespace"
        }

        val views = linkedMapOf<String, Configuration>()
        for (namespace in spec.namespaces) {
            val sourceConfiguration = project.configurations.maybeCreate(
                namespace.sourceConfiguration
            ).apply {
                isCanBeConsumed = false
                isCanBeResolved = false
            }
            val viewSpec = objects.newInstance(RemappedViewSpec::class.java)
            viewSpec.sourceNamespace.set(namespace.sourceNamespace)
            viewSpec.targetNamespace.set(spec.targetNamespace)
            viewSpec.discriminator.set(
                spec.discriminator.get() + "-from-" + artifactToken(namespace.sourceNamespace)
            )
            if (namespace.mappingFile.isPresent) {
                viewSpec.mappingFile.set(namespace.mappingFile)
            }
            viewSpec.classpath.from(spec.classpath)
            viewSpec.stepSpecs.set(spec.stepSpecs)

            views[namespace.sourceNamespace] = createRemappedView(
                environmentId,
                sourceConfiguration.name,
                defaultViewName(environmentId, sourceConfiguration.name),
                viewSpec
            )
        }
        return RemappedViewSet(
            baseName,
            views.toMap(),
            project.files(views.values)
        )
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

    private fun createRemappedView(
        environmentId: String,
        source: String,
        name: String,
        spec: RemappedViewSpec
    ): Configuration {
        val artifactType = spec.artifactTypeToken.orNull ?: spec.targetNamespace.get().let {
            "unimined-remapped-$environmentId-$it" + spec.discriminator.get()
        }
        val view = project.configurations.create(name) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(project.configurations.getByName(source))
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType
            )
        }
        registerRemapTransform(spec, artifactType)
        return view
    }

    private fun artifactToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "-")

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

    private fun defaultViewName(environmentId: String, source: String): String =
        if (environmentId == "main" || source.startsWith(environmentId, true)) {
            "${source}Remapped"
        } else {
            "${environmentId}${source.replaceFirstChar(Char::uppercase)}Remapped"
        }

    private val transformRegistrations = mutableMapOf<String, RemappedViewSpec>()

    private fun registerRemapTransform(spec: RemappedViewSpec, artifactType: String) {
        val existing = transformRegistrations[artifactType]
        if (existing != null) {
            require(
                existing.sourceNamespace.get() == spec.sourceNamespace.get() &&
                    existing.targetNamespace.get() == spec.targetNamespace.get() &&
                    existing.mappingFile.orNull?.asFile == spec.mappingFile.orNull?.asFile &&
                    existing.classpath.files == spec.classpath.files &&
                    existing.stepSpecs.getOrElse(emptyList()) == spec.stepSpecs.getOrElse(emptyList())
            ) {
                "remap target '${spec.targetNamespace.get()}' already registered with a different spec — " +
                    "set a discriminator on one of the views"
            }
            return
        }
        transformRegistrations[artifactType] = spec

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                artifactType
            )
            t.parameters { p ->
                p.sourceNamespace.set(spec.sourceNamespace)
                p.targetNamespace.set(spec.targetNamespace)
                if (spec.mappingFile.isPresent) p.mappingFile.set(spec.mappingFile)
                p.classpath.from(spec.classpath)
                p.stepSpecs.set(spec.stepSpecs)
            }
        }
    }

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
