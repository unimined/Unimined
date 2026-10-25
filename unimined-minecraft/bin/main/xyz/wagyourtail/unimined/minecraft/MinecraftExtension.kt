package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingExtension
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.task.ProduceMappingSnapshotTask
import xyz.wagyourtail.unimined.minecraft.internal.DefaultProviderContext
import xyz.wagyourtail.unimined.minecraft.internal.ProviderCacheSettings
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.nio.file.Path
import javax.inject.Inject

/**
 * The `minecraft { }` DSL: owns an independent environment per source set.
 * The legacy flat properties are aliases for [main], while [sourceSet]
 * configures additional environments.
 *
 * ```kotlin
 * minecraft {
 *     version = "1.20.1"      // convenience for version.set(...)
 *     side.set(Side.MERGED)   // default MERGED
 *     loader("forge", "47.2.0")
 *     provider.set("mavenizer") // optional DSL override
 * }
 * ```
 */
abstract class MinecraftExtension @Inject constructor(
    internal val project: Project,
    val objects: ObjectFactory,
    private val providers: ProviderFactory,
    private val exec: ExecOperations,
    private val dependencyFactory: DependencyFactory
) {

    /** Provider impls; populated via `META-INF/services` + engine plugins. */
    val registry = MinecraftProviderRegistry()

    /** Environments keyed by source-set name (`main`, `test`, `fabric`, ...). */
    val environments: NamedDomainObjectContainer<MinecraftEnvironment> =
        objects.domainObjectContainer(MinecraftEnvironment::class.java) { name ->
            MinecraftEnvironment(name, project, objects)
        }

    private val configuredSourceSets = mutableSetOf<String>()

    init {
        environments.maybeCreate("main")
        EnvironmentCopyRegistry.registerHandler(
            project,
            MinecraftEnvironmentCopyHandler(this)
        )
        project.pluginManager.withPlugin("java") {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.all { sourceSet ->
                environments.findByName(sourceSet.name)
                    ?.let { registerModConfigurations(it, sourceSet) }
            }
            environments.all { environment ->
                sourceSets.findByName(environment.environmentId)
                    ?.let { registerModConfigurations(environment, it) }
            }
        }
    }

    /** The default `main` source-set environment. */
    val main: MinecraftEnvironment
        get() = environments.getByName("main")

    /** Default source-set Minecraft version id. */
    val version: Property<String>
        get() = main.version

    /** Default source-set client/server/merged side. */
    val side: Property<Side>
        get() = main.side

    /** Default source-set provider override. */
    val provider: Property<String>
        get() = main.provider

    private fun registerModConfigurations(
        environment: MinecraftEnvironment,
        sourceSet: org.gradle.api.tasks.SourceSet
    ) {
        ensureModConfigurations(environment)
        environment.bindSourceSet(sourceSet)
    }

    internal fun ensureModConfigurations(environment: MinecraftEnvironment) {
        if (!configuredSourceSets.add(environment.environmentId)) return

        project.configurations.maybeCreate(
            environment.modImplementationConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modRuntimeOnlyConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
    }

    /** Get or create the environment associated with [sourceSetName]. */
    fun sourceSet(sourceSetName: String): MinecraftEnvironment =
        environments.maybeCreate(sourceSetName)

    /** Configure a source-set-specific Minecraft environment. */
    fun sourceSet(
        sourceSetName: String,
        action: Action<MinecraftEnvironment>
    ): MinecraftEnvironment = sourceSet(sourceSetName).also(action::execute)

    /** Groovy DSL overload for a source-set-specific environment. */
    fun sourceSet(
        sourceSetName: String,
        @DelegatesTo(value = MinecraftEnvironment::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): MinecraftEnvironment = sourceSet(sourceSetName).also { environment ->
        action.delegate = environment
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** Resolve a stable same-/cross-project environment reference. */
    internal fun environment(reference: EnvironmentReference): MinecraftEnvironment {
        val sourceProject = if (reference.projectPath == project.path) {
            project
        } else {
            project.project(reference.projectPath)
        }
        val extension = sourceProject.extensions.findByType(MinecraftExtension::class.java)
            ?: throw GradleException(
                "Project '${sourceProject.path}' does not have a Unimined Minecraft " +
                    "extension; apply the minecraft or frontend plugin before sharing " +
                    "environment '${reference.environmentId}'"
            )
        return extension.sourceSet(reference.environmentId)
    }

    /** Configure the modloader layer on the default `main` environment. */
    fun loader(name: String, version: String) {
        main.loader(name, version)
    }

    private val validatingCombinations = ThreadLocal<MutableSet<String>>()

    /** Validate version, loader, and dev-namespace compatibility for combined outputs. */
    internal fun validateCombinations(environment: MinecraftEnvironment) {
        if (environment.combinedWith.isEmpty()) return
        val targetKey = "${project.path}:${environment.environmentId}"
        val validating = validatingCombinations.get() ?: mutableSetOf<String>().also {
            validatingCombinations.set(it)
        }
        if (!validating.add(targetKey)) return
        try {
            rejectCombinationCycles(environment, linkedSetOf(targetKey))
            for (sourceReference in environment.combinedWith) {
                val source = environment(sourceReference)
                val sourceVersion = source.version.get()
                val targetVersion = environment.version.get()
                if (sourceVersion != targetVersion) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                            "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                            "Minecraft versions differ ($targetVersion != $sourceVersion)"
                    )
                }

                val targetLoader = environment.loaderSpec.orNull?.name
                val sourceLoader = source.loaderSpec.orNull?.name
                if (targetLoader != null && sourceLoader != null && targetLoader != sourceLoader) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                            "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                            "loaders differ ($targetLoader != $sourceLoader)"
                    )
                }

                val targetNamespace = devNamespace(project.path, environment.environmentId)
                val sourceNamespace = devNamespace(
                    sourceReference.projectPath,
                    sourceReference.environmentId
                )
                if (targetNamespace != null && sourceNamespace != null &&
                    targetNamespace != sourceNamespace
                ) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                            "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                            "dev namespaces differ ($targetNamespace != $sourceNamespace)"
                    )
                }
            }
        } finally {
            validating.remove(targetKey)
        }
    }

    private fun rejectCombinationCycles(
        environment: MinecraftEnvironment,
        path: LinkedHashSet<String>
    ) {
        for (sourceReference in environment.combinedWith) {
            val key = "${sourceReference.projectPath}:${sourceReference.environmentId}"
            if (!path.add(key)) {
                throw GradleException(
                    "Cyclic Unimined source-set combination involving " +
                        path.joinToString(" -> ") + " -> " + key
                )
            }
            rejectCombinationCycles(environment(sourceReference), path)
            path.remove(key)
        }
    }

    private fun devNamespace(projectPath: String, environmentId: String): String? {
        val sourceProject = if (projectPath == project.path) {
            project
        } else {
            project.project(projectPath)
        }
        val extension = sourceProject.extensions.getByType(MinecraftExtension::class.java)
        return extension.artifacts(environmentId).get().mappings.get()
            .namespaces.lastOrNull()
    }

    /** Build the provider-facing spec for the default `main` environment. */
    fun spec(): MinecraftSpec = main.spec()

    /** Build the provider-facing spec for a named source-set environment. */
    fun spec(environmentId: String): MinecraftSpec = sourceSet(environmentId).spec()

    private val ctx: ProviderContext by lazy {
        val cacheSettings = ProviderCacheSettings.from(project)
        DefaultProviderContext(
            project.layout, objects, dependencyFactory, providers, exec,
            Logging.getLogger(MinecraftExtension::class.java),
            cacheSettings.offline,
            cacheSettings.forceReload,
            cacheSettings.refreshDependencies,
            cacheSettings.refreshDownloads,
            cacheSettings.rebuildArtifacts,
            cacheSettings.metadataCacheTtl
        )
    }

    private val mappingExtension: MappingExtension
        get() = project.extensions.getByType(MappingExtension::class.java)

    private val mavenResolver = java.util.function.Function<String, Path> { notation ->
        resolveMavenNotation(notation)
    }

    private val mappingDataProviders = mutableMapOf<String, Provider<xyz.wagyourtail.unimined.api.mapping.MappingData>>()

    /** Configure mappings for the default `main` environment. */
    fun mappings(action: Action<MappingsSpecImpl>) = main.mappings(action)

    /** Groovy DSL overload for the default environment's mapping sources. */
    fun mappings(
        @DelegatesTo(value = MappingsSpecImpl::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = main.mappings(action)

    /** Configure mappings for a named environment. */
    fun mappings(environmentId: String, action: Action<MappingsSpecImpl>) =
        sourceSet(environmentId).mappings(action)

    /** Groovy DSL overload for a named environment's mapping sources. */
    fun mappings(
        environmentId: String,
        @DelegatesTo(value = MappingsSpecImpl::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = sourceSet(environmentId).mappings(action)

    private fun mappingDataProvider(environment: MinecraftEnvironment): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        synchronized(mappingDataProviders) {
            mappingDataProviders.getOrPut(environment.environmentId) {
                providers.provider {
                    environment.validateCombinations()
                    mappingExtension.resolve(
                        environment.mappingsSpec, environment.environmentId, mavenResolver
                    )
                }
            }
        }

    private val resolvedEnvironments = mutableMapOf<String, MinecraftArtifacts>()

    private fun resolved(environment: MinecraftEnvironment): MinecraftArtifacts =
        synchronized(resolvedEnvironments) {
            resolvedEnvironments.getOrPut(environment.environmentId) {
                val spec = environment.spec()
                registry.select(spec, environment.provider.orNull).resolve(spec, ctx)
            }
        }

    /** Resolved mapping data for the default `main` environment. */
    fun mappingData(): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        mappingData("main")

    /** Resolved mapping data for [environmentId]. */
    fun mappingData(environmentId: String): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        mappingDataProvider(sourceSet(environmentId))

    /** Resolve a `maven("g:a:v[:classifier]")` source lazily via a
     *  non-transitive detached configuration. */
    private fun resolveMavenNotation(notation: String) =
        project.configurations.detachedConfiguration(
            project.dependencies.create(notation)
        ).apply { isTransitive = false }
            .files.single().toPath()

    /**
     * Register a task producing the serialized `MappingSnapshot` for
     * `from -> to` — feed `RemapJarTask.mappingFile` /
     * `RemappedViewSpec.mappingFile`.
     */
    fun mappingSnapshot(from: String, to: String): Provider<org.gradle.api.file.RegularFile> {
        return mappingSnapshot("main", from, to)
    }

    /**
     * Register the mapping snapshot task for one source-set environment.
     */
    fun mappingSnapshot(
        environmentId: String,
        from: String,
        to: String
    ): Provider<org.gradle.api.file.RegularFile> {
        val environment = sourceSet(environmentId)
        val safeEnvironment = environmentId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val task = project.tasks.register(
            "produceMappingSnapshot_${safeEnvironment}_${from}_${to}",
            ProduceMappingSnapshotTask::class.java
        ) { t ->
            t.environmentId.set(environmentId)
            t.mappingFiles.from(providers.provider {
                mappingExtension.materializeSources(
                    environment.mappingsSpec,
                    environmentId,
                    mavenResolver
                )
            })
            t.sourceNamespace.set(from)
            t.targetNamespace.set(to)
            t.snapshotFile.set(
                project.layout.buildDirectory.file(
                    "unimined/mappings/$safeEnvironment/$from-$to.json"
                )
            )
        }
        return task.flatMap { it.snapshotFile }
    }

    /**
     * The resolved environment artifacts — provider resolution is deferred
     * to first query and memoized; downstream plugins wire the returned
     * providers, never raw files.
     */
    fun artifacts(): Provider<MinecraftArtifacts> = artifacts("main")

    /** Resolve artifacts for a named source-set environment. */
    fun artifacts(environmentId: String): Provider<MinecraftArtifacts> =
        providers.provider {
        val environment = sourceSet(environmentId)
        val providerArtifacts = resolved(environment)
        val environmentMappingData = mappingDataProvider(environment)
        object : MinecraftArtifacts by providerArtifacts {
            override val mappings: Provider<MappingTree> = providers.provider {
                if (environment.mappingsSpec.effectiveSources().isEmpty()) {
                    providerArtifacts.mappings.get()
                } else {
                    environmentMappingData.get().view<MappingTree>()
                        ?: throw IllegalStateException(
                            "configured mappings do not expose the portable MappingTree view"
                        )
                }
            }
        }
    }

}
