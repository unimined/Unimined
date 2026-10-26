package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingExtension
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.task.ProduceMappingBundleTask
import xyz.wagyourtail.unimined.minecraft.internal.DefaultProviderContext
import xyz.wagyourtail.unimined.minecraft.internal.ProviderCacheSettings
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeMinecraftSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.minecraft.transform.MinecraftArtifactTransform
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
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

    /**
     * Loader-name → default mod-dependency source namespaces, contributed
     * by transform plugins at apply time (e.g. fabric → `intermediary`).
     * Consulted when an environment does not declare
     * [MinecraftEnvironment.modSourceNamespaces].
     */
    val loaderModSourceNamespaces = mutableMapOf<String, List<String>>()

    /**
     * Resolvable dev-namespace MC artifact views, keyed by environment —
     * populated by the transform host's classpath wiring. When absent the
     * raw view serves the classpath.
     */
    val devViewConfigurations = mutableMapOf<String, Configuration>()

    private val configuredSourceSets = mutableSetOf<String>()
    private val boundEnvironments = linkedMapOf<String, SourceSet>()
    private val boundHandlers = mutableListOf<EnvironmentBoundHandler>()
    private val rawViewConfigurations = mutableMapOf<String, Configuration>()
    private val registeredTransformEnvironments = mutableSetOf<String>()
    private var localRepoRegistered = false

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

    /** SAM listener for environment↔source-set binding. */
    fun interface EnvironmentBoundHandler {
        fun onBound(environment: MinecraftEnvironment, sourceSet: SourceSet)
    }

    /**
     * Register [handler] for environment↔source-set binding — invoked
     * immediately for already-bound environments, then for each future
     * binding. Transform/source plugins use this to attach their
     * environment-scoped classpath wiring.
     */
    fun onEnvironmentBound(handler: EnvironmentBoundHandler) {
        boundHandlers += handler
        boundEnvironments.forEach { (environmentId, sourceSet) ->
            handler.onBound(environments.getByName(environmentId), sourceSet)
        }
    }

    private fun registerModConfigurations(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        ensureModConfigurations(environment)
        if (boundEnvironments.putIfAbsent(environment.environmentId, sourceSet) != null) {
            return
        }
        environment.bindSourceSet(sourceSet)
        wireClasspath(environment, sourceSet)
        boundHandlers.forEach { it.onBound(environment, sourceSet) }
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

    /**
     * Attach this environment's Minecraft artifact and resolved libraries
     * to the bound source set's compile/runtime classpaths.
     *
     * The MC artifact rides the local-repo stub mechanism: a lazily-added
     * `unimined.generated` module dep on [MinecraftEnvironment.minecraftConfigurationName]
     * resolves through the per-environment resolvable view
     * ([minecraftRawViewName]), whose `artifactType` request triggers
     * [MinecraftArtifactTransform] — the real jar materializes at
     * resolution time, cacheably, including during IDE sync. The transform
     * host may additionally wire a dev-namespace view
     * ([devViewConfigurations]); the classpath contribution picks it up
     * lazily so plugin ordering does not matter.
     */
    private fun wireClasspath(environment: MinecraftEnvironment, sourceSet: SourceSet) {
        ensureLocalRepo()
        val environmentId = environment.environmentId

        val minecraftBucket = project.configurations.maybeCreate(
            environment.minecraftConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        // added lazily — the coordinate needs the script-final spec and
        // the marker stub is (re)written alongside the dependency
        minecraftBucket.withDependencies { deps ->
            deps.add(project.dependencies.create(stubDependencyNotation(environment)))
        }

        val librariesBucket = project.configurations.maybeCreate(
            environment.minecraftLibrariesConfigurationName
        ).apply {
            isCanBeConsumed = false
            // resolvable — serves both extendsFrom'd classpath deps and
            // FileCollection inputs (remap analysis classpaths)
            isCanBeResolved = true
        }
        librariesBucket.withDependencies { deps ->
            deps.addAll(artifacts(environmentId).get().libraries.get())
        }
        project.configurations.named(sourceSet.compileClasspathConfigurationName) {
            it.extendsFrom(librariesBucket)
        }
        project.configurations.named(sourceSet.runtimeClasspathConfigurationName) {
            it.extendsFrom(librariesBucket)
        }

        registerMinecraftTransform(environment)

        val rawView = project.configurations.create(environment.minecraftRawViewName) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(minecraftBucket)
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                rawArtifactType(environmentId)
            )
        }
        rawViewConfigurations[environmentId] = rawView

        // Picks the raw view when the provider reports an identity pipeline
        // or the resolved dev namespace equals the source namespace, so
        // identity environments never materialize a duplicate dev jar.
        val contribution = objects.fileCollection().from(providers.provider {
            val devView = devViewConfigurations[environmentId] ?: return@provider rawView
            val providerArtifacts = artifacts(environmentId).get()
            if (providerArtifacts.namespaceMode.get() == NamespaceMode.IDENTITY) {
                return@provider rawView
            }
            val dev = devNamespace(environmentId).orNull
            val source = sourceNamespace(environmentId).orNull
            if (dev != null && dev == source) rawView else devView
        })
        sourceSet.compileClasspath = sourceSet.compileClasspath + contribution
        sourceSet.runtimeClasspath = sourceSet.runtimeClasspath + contribution

        val extraDependencies = objects.fileCollection().from(providers.provider {
            artifacts(environmentId).get().extraDependencies
        })
        sourceSet.compileClasspath = sourceSet.compileClasspath + extraDependencies
        sourceSet.runtimeClasspath = sourceSet.runtimeClasspath + extraDependencies

        sourceSet.java.srcDir(objects.fileCollection().from(providers.provider {
            artifacts(environmentId).get().extraCompileSources
        }))
    }

    /** `artifactType` for the raw (source-namespace) MC artifact view. */
    fun rawArtifactType(environmentId: String): String = "unimined-mc-raw-$environmentId"

    /** `artifactType` for the dev-namespace MC artifact view. */
    fun devArtifactType(environmentId: String): String = "unimined-mc-dev-$environmentId"

    /** The resolvable raw-artifact view for [environmentId], if bound. */
    fun rawView(environmentId: String): Configuration? = rawViewConfigurations[environmentId]

    private fun registerMinecraftTransform(environment: MinecraftEnvironment) {
        val environmentId = environment.environmentId
        if (!registeredTransformEnvironments.add(environmentId)) return
        val cacheSettings = ProviderCacheSettings.from(project)
        val cacheDir = project.layout.projectDirectory
            .dir(".gradle/unimined/native-cache").asFile.toPath()

        project.dependencies.registerTransform(MinecraftArtifactTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                rawArtifactType(environmentId)
            )
            t.parameters { p ->
                p.environmentId.set(environmentId)
                p.version.set(environment.version)
                p.side.set(environment.side.map { it.name })
                p.artifactKey.set(environment.side.map { sideKey(it).name })
                environment.provider.orNull?.let { p.producerName.set(it) }
                p.cacheDir.set(cacheDir.toString())
                p.offline.set(cacheSettings.offline)
                p.refreshUniminedDownloads.set(cacheSettings.refreshUniminedDownloads)
                p.rebuildUniminedArtifacts.set(cacheSettings.rebuildUniminedArtifacts)
                p.artifactFormatVersion.set(providers.provider {
                    val spec = RuntimeMinecraftSpec(
                        environmentId = environmentId,
                        sourceSetName = environmentId,
                        version = RuntimeVersionSpec(environment.version.get()),
                        side = environment.side.get(),
                        loader = null
                    )
                    MinecraftArtifactProducerRegistry()
                        .select(spec, environment.provider.orNull)
                        .artifactFormatVersion
                })
                cacheSettings.metadataCacheTtl?.let { p.metadataCacheTtl.set(it.toString()) }
            }
        }
    }

    private fun sideKey(side: Side): ArtifactKey = when (side) {
        Side.CLIENT -> ArtifactKey.CLIENT
        Side.SERVER -> ArtifactKey.SERVER
        Side.MERGED -> ArtifactKey.MERGED
    }

    /** The dev namespace for [environmentId] — explicit
     *  `MinecraftEnvironment.devNamespace`, else the resolved mapping
     *  tree's last namespace. Evaluated lazily. */
    fun devNamespace(environmentId: String): Provider<String> =
        sourceSet(environmentId).devNamespace.orElse(providers.provider {
            artifacts(environmentId).get().mappings.get().namespaces.last()
        })

    /** The raw artifact's namespace for [environmentId] — the resolved
     *  mapping tree's first namespace. Evaluated lazily. */
    fun sourceNamespace(environmentId: String): Provider<String> =
        artifacts(environmentId).map { it.mappings.get().namespaces.first() }

    /** Effective mod-dependency source namespaces — the environment's
     *  explicit list, else the loader's contributed default. An empty
     *  declaration counts as unset (list properties default to `[]`). */
    fun modSourceNamespaces(environmentId: String): Provider<List<String>> =
        providers.provider {
            val environment = sourceSet(environmentId)
            val explicit = environment.modSourceNamespaces.getOrElse(emptyList())
            if (explicit.isNotEmpty()) {
                explicit
            } else {
                loaderModSourceNamespaces[environment.loaderSpec.orNull?.name] ?: emptyList()
            }
        }

    private fun stubDependencyNotation(environment: MinecraftEnvironment): String {
        val artifactId = "minecraft-${environment.environmentId}"
        val version = environment.version.get()
        LocalMavenPublisher.publish(
            localRepoDir, STUB_GROUP, artifactId, version,
            mapOf("" to markerJar(localRepoDir))
        )
        return "$STUB_GROUP:$artifactId:$version"
    }

    /** Local maven repo carrying `unimined.generated` stub modules. */
    val localRepoDir: Path
        get() = project.layout.projectDirectory
            .dir(".gradle/unimined/local-repo").asFile.toPath()

    private fun ensureLocalRepo() {
        if (localRepoRegistered) return
        localRepoRegistered = true
        project.repositories.maven { repo ->
            repo.name = "uniminedGenerated"
            repo.url = localRepoDir.toUri()
            repo.content { it.includeGroup(STUB_GROUP) }
        }
    }

    private fun markerJar(repoDir: Path): Path {
        val marker = repoDir.resolve(".marker/marker.jar")
        if (Files.notExists(marker)) {
            Files.createDirectories(marker.parent)
            JarOutputStream(Files.newOutputStream(marker), Manifest()).close()
        }
        return marker
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
     * Register a task producing the serialized `MappingBundle` for
     * `from -> to` — feed `RemapJarTask.mappingFile` /
     * `RemappedViewSpec.mappingFile`.
     */
    fun mappingBundle(from: String, to: String): Provider<org.gradle.api.file.RegularFile> {
        return mappingBundle("main", from, to)
    }

    /**
     * Register the mapping bundle task for one source-set environment.
     */
    fun mappingBundle(
        environmentId: String,
        from: String,
        to: String
    ): Provider<RegularFile> {
        val environment = sourceSet(environmentId)
        val safeEnvironment = environmentId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val task = project.tasks.register(
            "produceMappingBundle_${safeEnvironment}_${from}_${to}",
            ProduceMappingBundleTask::class.java
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
            t.bundleFile.set(
                project.layout.buildDirectory.file(
                    "unimined/mappings/$safeEnvironment/$from-$to.json"
                )
            )
        }
        return task.flatMap { it.bundleFile }
    }

    /**
     * Register the mapping bundle task with lazily-evaluated
     * namespaces — for host wiring that cannot know the namespaces at
     * configuration time. [nameSuffix] must be stable per bundle.
     */
    fun mappingBundle(
        environmentId: String,
        nameSuffix: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<RegularFile> {
        val environment = sourceSet(environmentId)
        val safeEnvironment = environmentId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val task = project.tasks.register(
            "produceMappingBundle_${safeEnvironment}_$nameSuffix",
            ProduceMappingBundleTask::class.java
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
            t.bundleFile.set(
                project.layout.buildDirectory.file(
                    "unimined/mappings/$safeEnvironment/$nameSuffix.json"
                )
            )
        }
        return task.flatMap { it.bundleFile }
    }

    /**
     * Lazily computes the serialized `MappingBundle` for `from -> to`
     * as a string provider — the input consumed by
     * `RemapDependencyTransform` parameters. Materialization and
     * composition run when the provider is evaluated (at transform
     * execution), because artifact transforms execute during dependency
     * resolution outside the task graph and cannot depend on a bundle
     * producer task.
     *
     * Each declared source materializes through the source-provider
     * pipeline (maven resolution, archive unwrapping) and reads through
     * the mapping-provider pipeline (format hints, `mapNamespace`
     * relabeling) — maven coordinates resolve through detached
     * non-transitive configurations created at evaluation.
     */
    fun mappingBundleJson(
        environmentId: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<String> {
        val environment = sourceSet(environmentId)
        val spec = environment.mappingsSpec
        val dependencies = project.dependencies
        val configurations = project.configurations
        return providers.provider {
            spec.effectiveSources()
                .filterIsInstance<MappingsSpecImpl.Source.Builtin>()
                .firstOrNull()?.let {
                    throw GradleException(
                        "mappings { ${it.name}() }: builtin channels " +
                            "resolve through provider-native's manifest " +
                            "machinery — not yet implemented; use file() " +
                            "or maven() meanwhile"
                    )
                }
            val data = mappingExtension.resolve(spec, environmentId) { notation ->
                configurations.detachedConfiguration(dependencies.create(notation))
                    .apply { isTransitive = false }
                    .singleFile.toPath()
            }
            val tree = data.view<MappingTree>() ?: error(
                "mapping provider did not expose a portable MappingTree view"
            )
            MappingBundle.capture(from.get(), to.get(), tree).toJson()
        }
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

    private companion object {
        const val STUB_GROUP = "unimined.generated"
    }

}
