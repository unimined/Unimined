package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.api.Deprecations
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.writeAtomically

import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.internal.DefaultProviderContext
import xyz.wagyourtail.unimined.minecraft.internal.EnvironmentBinding
import xyz.wagyourtail.unimined.minecraft.internal.CombinationValidation
import xyz.wagyourtail.unimined.minecraft.internal.LocalStubRepo
import xyz.wagyourtail.unimined.minecraft.internal.MappingResolution
import xyz.wagyourtail.unimined.minecraft.internal.ProviderCacheSettings
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.minecraft.transform.ProductArtifactTransform
import java.nio.file.Files
import org.gradle.jvm.toolchain.JavaToolchainService
import java.nio.file.Path
import kotlin.io.path.readText
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
    internal val providers: ProviderFactory,
    private val exec: ExecOperations,
    private val dependencyFactory: DependencyFactory
) {

    /** Provider impls; populated via `META-INF/services` + engine plugins. */
    val registry = ProductProviderRegistry()

    /** Mapping/artifact resolution seam — [MappingResolution] owns the
     *  resolved-data caches, channel resolution, namespace read-model,
     *  and mapping-bundle producers; the public verbs delegate. */
    internal val resolution = MappingResolution(this)

    /** Environment↔source-set binding seam — [EnvironmentBinding]
     *  owns the bind lifecycle, classpath wiring, and transform
     *  registration; the verbs delegate. */
    internal val binding = EnvironmentBinding(this)

    /** `unimined.generated` local maven stub repo — [LocalStubRepo]
     *  owns the marker-module publication that carries the MC
     *  artifact into the transform pipeline. */
    internal val stubRepo = LocalStubRepo(project)

    /** Cross-env `combineWith` validation — [CombinationValidation]
     *  owns version/loader/dev-namespace compat checks + cycle
     *  rejection across project boundaries. */
    internal val combinations = CombinationValidation(this)

    /** Environments keyed by source-set name (`main`, `test`, `fabric`, ...). */
    val environments: NamedDomainObjectContainer<MinecraftEnvironment> =
        objects.domainObjectContainer(MinecraftEnvironment::class.java) { name ->

            // newInstance — decoration is what dispatches Groovy calls
            // (env.jarmod(...)) to the ExtensionAware extensions
            objects.newInstance(MinecraftEnvironment::class.java, name, project, objects)
        }.also { container ->
        // implicit parent-container inheritance: every env combines its
        // lexical parent (default `main`) unless inheritParent is false —
        // deferred to post-evaluation so flags inside configure blocks win
        container.configureEach { env ->

        }
    }

    /**
     * Whether tool execs, source-set compiles, and run launchers resolve
     * Gradle java toolchains — the global default for
     * [MinecraftEnvironment.useToolchains] (per-env override).
     * 1.x `useToolchains` semantics — opt-out: when disabled the build
     * JVM must satisfy each environment's java requirement.
     */
    val useToolchains: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

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

    /** Set by transform-family wiring when an env's JAR pipeline carries
     *  env-scoped work (jar processors, contributed/declared steps) — the
     *  raw→dev transform is the only host for it, so the dev classpath
     *  must materialize the dev artifact even when the identity shortcut
     *  below would otherwise pick the raw view. Keyed by environmentId. */
    val materializeDevArtifact = mutableMapOf<String, Provider<Boolean>>()

    /**
     * Resolvable dev-namespace views over the mod dependency buckets,
     * keyed `"<environmentId>:<bucketConfigurationName>"` — populated
     * by the transform host's classpath wiring. `genModSources` reads
     * these for the remapped artifact-type token its decompile
     * transform chains onto.
     */
    val modDevViewConfigurations = mutableMapOf<String, Configuration>()

    internal val rawViewConfigurations = mutableMapOf<String, Configuration>()

    internal val modSourceViewConfigurations = mutableMapOf<String, Configuration>()

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
                // declaring an env declares its Gradle source set —
                // without this the env stays inert until something
                // else creates one (no compile/run/jar tasks)
                registerModConfigurations(
                    environment,
                    sourceSets.maybeCreate(environment.environmentId))
            }
        }
        project.afterDslEval {
            // Materialize implicit-parent inheritance post-DSL (so
            // `inheritParent`/`isAuxEnv` are final) but before task-graph
            // resolution: DEPENDENCIES mutates configuration extendsFrom,
            // a dead edge once consumers have resolved — the lazy
            // `combinedWith` first-read can land too late. Sections that
            // set conventions/providers (VERSION/LOADER/MAPPINGS) survive
            // lazily; conf edges cannot. The lazy trigger stays as a
            // fallback for envs created inside later lifecycle hooks.
            environments.forEach(MinecraftEnvironment::applyImplicitParent)
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

    /** Default source-set product id (`minecraft:native` unless a
     *  dist preset or override declares otherwise). */
    val product: Property<String>
        get() = main.product

    /**
     * Default source-set java floor — raises the environment's
     * effective java requirement above the version-json
     * `javaVersion` (`minecraft { javaVersion = 21 }`). Loader
     * transform descriptors convention it (cleanroom→21, ornithe→17);
     * a user value always wins.
     */
    val javaVersion: Property<Int>
        get() = main.javaVersion

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
    fun onEnvironmentBound(handler: EnvironmentBoundHandler) =
        binding.onEnvironmentBound(handler)

    private fun registerModConfigurations(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) = binding.registerModConfigurations(environment, sourceSet)

    internal fun ensureModConfigurations(environment: MinecraftEnvironment) =
        binding.ensureModConfigurations(environment)

    /** `artifactType` for the raw (source-namespace) MC artifact view. */
    fun rawArtifactType(environmentId: String): String = "unimined-mc-raw-$environmentId"

    /** `artifactType` for the dev-namespace MC artifact view. */
    fun devArtifactType(environmentId: String): String = "unimined-mc-dev-$environmentId"

    /** `artifactType` for the mod-source-namespace MC artifact view —
     *  `rawArtifactType` when `modSourceNamespaces` equals the
     *  environment source namespace (nothing to remap). */
    fun modSourceArtifactType(environmentId: String): String = "unimined-mc-modsource-$environmentId"

    /** The resolvable raw-artifact view for [environmentId], if bound. */
    fun rawView(environmentId: String): Configuration? = rawViewConfigurations[environmentId]

    /** The resolvable mod-source-artifact view for [environmentId], if bound. */
    fun modSourceView(environmentId: String): Configuration? = modSourceViewConfigurations[environmentId]

    internal fun sideKey(side: Side): ArtifactKey = when (side) {
        Side.CLIENT -> ArtifactKey.CLIENT
        Side.SERVER -> ArtifactKey.SERVER
        Side.MERGED -> ArtifactKey.MERGED
    }

    /** The dev namespace for [environmentId] — explicit
     *  `MinecraftEnvironment.devNamespace`, else the first *named*
     *  namespace the resolved sources flagged (1.x `outputs(ns, named)`
     *  semantics), else the composed tree's last namespace. Evaluated
     *  lazily. */
    fun devNamespace(environmentId: String): Provider<String> =
        resolution.devNamespace(environmentId)

    /** The raw artifact's namespace for [environmentId] — the resolved
     *  mapping tree's first namespace. Evaluated lazily. */
    fun sourceNamespace(environmentId: String): Provider<String> =
        resolution.sourceNamespace(environmentId)


    /** Effective mod-dependency source namespaces — the environment's
     *  explicit list, else the loader's contributed default, else the
     *  base (production) namespace for no-loader environments. An empty
     *  declaration counts as unset (list properties default to `[]`). */
    fun modSourceNamespaces(environmentId: String): Provider<List<String>> =
        resolution.modSourceNamespaces(environmentId)


    /** Whether [environmentId]'s production namespace equals its dev
     *  namespace — mod artifacts need no remap between dev and
     *  production. Identity environments emit a single jar/coordinate
     *  for both development and production; the dev/prod split
     *  collapses. Evaluated lazily — may resolve the environment's
     *  mappings when the namespaces are not declared explicitly. */
    fun isIdentityRemap(environmentId: String): Provider<Boolean> =
        resolution.isIdentityRemap(environmentId)


    /** Local maven repo carrying `unimined.generated` stub modules. */
    val localRepoDir: Path
        get() = stubRepo.localRepoDir

    /** Get or create the environment associated with [sourceSetName]. */
    fun sourceSet(sourceSetName: String): MinecraftEnvironment =
        environments.maybeCreate(sourceSetName)

    /** Alias for [environments] — supports container delegates like
     *  `val fabric by sourceSets.creating { }`. */
    val sourceSets: NamedDomainObjectContainer<MinecraftEnvironment>
        get() = environments

    /** Configure a source-set-specific Minecraft environment. Envs
     *  declared at `minecraft {}` top level implicitly inherit `main`. */
    fun sourceSet(
        sourceSetName: String,
        action: Action<MinecraftEnvironment>
    ): MinecraftEnvironment =
        sourceSetInternal(sourceSetName, "main", action)

    /** Create/configure an env with an explicit lexical parent — used by
     *  nested `sourceSet` calls so the enclosing env becomes the parent. */
    internal fun sourceSetInternal(
        sourceSetName: String,
        parentEnvId: String,
        action: Action<MinecraftEnvironment>
    ): MinecraftEnvironment = sourceSet(sourceSetName).also { env ->
        env.parentEnvId = parentEnvId
        action.execute(env)
    }

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
        return extension.environments.findByName(reference.environmentId)
            ?: throw GradleException(
                "unimined: no minecraft environment named " +
                "'${reference.environmentId}' in project " +
                "'${sourceProject.path}' (referenced from environment " +
                "'${project.path}') — declared envs: " +
                extension.environments.names.joinToString()
            )
    }

    /** Configure the modloader layer on the default `main`
     *  environment, letting the descriptor resolve the version
     *  (`foxloader` picks the newest release pairing with the dist). */
    fun loader(name: String) {
        main.loader(name)
    }

    /** Configure the modloader layer on the default `main` environment. */
    fun loader(name: String, version: String) {
        main.loader(name, version)
    }

    /** Groovy DSL — `loader "forge", "47.2.0", { nestedJars { } }` —
     *  the closure delegates to `main`'s transform spec. */
    fun loader(name: String, version: String, action: Closure<*>) =
        main.loader(name, version, action)

    /** Groovy DSL — `loader "foxloader", { }` single-arg form. */
    fun loader(name: String, action: Closure<*>) =
        main.loader(name, action)

    /** Groovy DSL — `transform { nestedJars { } }` on `main`. */
    fun transform(action: Closure<*>) =
        action.delegatingTo(main.transformSpec())

    /** Configure `main` as a Better Than Adventure dist —
     *  `provider("conversion")` + `product("minecraft:bta")` preset. */
    fun bta(action: Action<DistSpec>) = main.bta(action)

    /** Groovy-DSL counterpart to [bta]. */
    fun bta(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        main.bta(action)

    /** Configure `main` as a ReIndev dist —
     *  `provider("conversion")` + `product("minecraft:reindev")` preset. */
    fun reindev(action: Action<DistSpec>) = main.reindev(action)

    /** Groovy-DSL counterpart to [reindev]. */
    fun reindev(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        main.reindev(action)

    /** Configure `main` as an NSSS dist —
     *  `provider("conversion")` + `product("minecraft:nsss")` preset;
     *  no upstream index, declare `file()`/`url()` inside. */
    fun nsss(action: Action<DistSpec>) = main.nsss(action)

    /** Groovy-DSL counterpart to [nsss]. */
    fun nsss(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        main.nsss(action)

    /** Configure `main` against a user-supplied dist jar —
     *  declared-source seam; `product` conventions to `custom:<name>`. */
    fun custom(name: String, action: Action<DistSpec>) = main.custom(name, action)

    /** Groovy-DSL counterpart to [custom]. */
    fun custom(name: String, @DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        main.custom(name, action)

    // region 1.x deprecation bridge — patcher blocks became
    // loader("name", "version"); the deprecated overloads fail with
    // the migration target in the message (plan §Phase 8)

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun fabric(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("fabric")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun quilt(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("quilt")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun forge(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("forge")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun neoforge(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("neoforge")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun legacyFabric(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("legacyFabric")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun babric(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("babric")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun ornithe(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("ornithe")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun modloader(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("modloader")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun risugami(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("risugami")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun jarmod(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("jarmod")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun nil(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("nil")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun flint(action: Action<Any>): Nothing =
        Deprecations.removedLoader(
            "flint",
            "maven.flintloader.net is offline, so `net.flintloader:*` " +
                    "artifacts are unresolvable. Reintroduce via " +
                    "LoaderRegistry.register + a mirrored repo if the " +
                    "host returns."
        )

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun foxLoader(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("foxLoader")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun bukkit(action: Action<Any>): Nothing =
        // "bukkit" is the family name — craftbukkit is the descriptor
        Deprecations.movedDsl("bukkit { }", "loader(\"craftbukkit\", \"<version>\")")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun spigot(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("spigot")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun paper(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("paper")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun folia(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("folia")

    @Deprecated("removed in 2.0", level = DeprecationLevel.ERROR)
    fun arch(action: Action<Any>): Nothing =
        Deprecations.loaderBlock("arch")

    // endregion

    internal fun validateCombinations(environment: MinecraftEnvironment) =
        combinations.validate(environment)

    /** Build the provider-facing spec for the default `main` environment. */
    fun spec(): ProductSpec = main.spec()

    /** Build the provider-facing spec for a named source-set environment. */
    fun spec(environmentId: String): ProductSpec = sourceSet(environmentId).spec()

    internal val providerContext: ProviderContext by lazy {
        val cacheSettings = ProviderCacheSettings.from(project)
        DefaultProviderContext(
            project.layout, objects, dependencyFactory, providers, exec,
            Logging.getLogger(MinecraftExtension::class.java),
            project.extensions.getByType(JavaToolchainService::class.java),
            useToolchains,
            xyz.wagyourtail.unimined.api.util.UniminedCache.global(project),
            cacheSettings.offline,
            cacheSettings.forceReload,
            cacheSettings.refreshDependencies,
            cacheSettings.refreshDownloads,
            cacheSettings.rebuildArtifacts,
            cacheSettings.metadataCacheTtl
        )
    }

    internal fun resolved(environment: MinecraftEnvironment): ProductArtifacts =
        resolution.resolved(environment)

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

    /** Resolved mapping data for the default `main` environment. */
    fun mappingData(): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        mappingData("main")

    /** Resolved mapping data for [environmentId]. */
    fun mappingData(environmentId: String): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        resolution.mappingDataProvider(sourceSet(environmentId))

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
    ): Provider<RegularFile> = resolution.mappingBundle(environmentId, from, to)

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
    ): Provider<RegularFile> = resolution.mappingBundle(environmentId, nameSuffix, from, to)

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
    ): Provider<String> = resolution.mappingBundleJson(environmentId, from, to)

    /**
     * File-backed variant of [mappingBundleJson] — the same payload
     * written once, content-addressed under the global cache's
     * `mappings-bundle/`, so transform params carry a file reference
     * rather than an inline `z64:` megastring. Preferred for
     * environment wiring; the string form remains for call sites
     * that genuinely need an inline value.
     */
    fun mappingBundleFile(
        environmentId: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<RegularFile> = resolution.mappingBundleFile(environmentId, from, to)

    /**
     * Register the `exportMappings` task (`exportMappings<Ss>` for
     * non-main) — declare `export { }` specs inside [action]; each
     * writes the environment's resolved mappings through a UML writer
     * (1.x `exportMappings` parity). Registration is on-demand like
     * `migrateMappings`, so no mapping resolution is wired into
     * projects that never export.
     */
    fun exportMappings(
        environmentId: String,
        action: Action<xyz.wagyourtail.unimined.mapping.task.ExportMappingsTask>
    ) {
        val name = "exportMappings" + if (environmentId == "main") ""
            else environmentId.replaceFirstChar(Char::uppercase)
        // registers its own produceMappingBundle task — must happen
        // outside the tasks.register below (nested registration is
        // illegal)
        val inputBundle = mappingBundle(
            environmentId, "export",
            devNamespace(environmentId),
            modSourceNamespaces(environmentId).map { it.first() }
        )
        project.tasks.register(
            name,
            xyz.wagyourtail.unimined.mapping.task.ExportMappingsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Exports '$environmentId' resolved mappings"
            t.environmentId.set(environmentId)
            t.inputBundleFile.set(inputBundle)
            action.execute(t)
        }
    }

    /** Groovy DSL overload —
     *  `exportMappings("main") { export { ... } }`. */
    fun exportMappings(
        environmentId: String,
        @DelegatesTo(
            value = xyz.wagyourtail.unimined.mapping.task.ExportMappingsTask::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) = exportMappings(environmentId, Action { action.delegatingTo(it) })

    /**
     * The resolved environment artifacts — provider resolution is deferred
     * to first query and memoized; downstream plugins wire the returned
     * providers, never raw files.
     */
    fun artifacts(): Provider<ProductArtifacts> = artifacts("main")

    /** Resolve artifacts for a named source-set environment. */
    fun artifacts(environmentId: String): Provider<ProductArtifacts> =
        providers.provider {
            val environment = sourceSet(environmentId)
            val providerArtifacts = resolution.resolved(environment)
            val environmentMappingData = resolution.mappingDataProvider(environment)
            object : ProductArtifacts by providerArtifacts {
                override val mappings: Provider<MappingTree> = providers.provider {
                    if (environment.mappingsSpec.effectiveSources().isEmpty()) {
                        providerArtifacts.mappings.get()
                    } else {
                        // declared-but-empty sources (e.g. a loader hop
                        // gated on another family) compose zero parts —
                        // fall back to the provider's tree rather than
                        // demanding a UML view of an empty composition
                        environmentMappingData.get().view<MappingTree>()
                            ?: providerArtifacts.mappings.get()
                    }
                }
            }
        }

}
