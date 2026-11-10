package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.mapping.MappingExtension
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.mapping.internal.MaterializedSourceSpec
import xyz.wagyourtail.unimined.mapping.UniminedRepos
import xyz.wagyourtail.unimined.mapping.task.ProduceMappingBundleTask
import java.nio.file.Path

/**
 * Mapping/artifact resolution seam for [MinecraftExtension] — owns the
 * resolved-data caches, builtin channel resolution (`mojmap`/`searge`/
 * `spigot`/`parchment`/`forgeBuiltinMCP`), the per-env resolved
 * [ProductArtifacts], the namespace read-model (`devNamespace`/
 * `sourceNamespace`/`modSourceNamespaces`/`isIdentityRemap`), and the
 * `mappingBundle`/`mappingBundleJson` producers. Public DSL verbs on
 * the extension delegate here.
 */
internal class MappingResolution(private val ext: MinecraftExtension) {

    private val project = ext.project
    private val providers = ext.providers

    /** Resolve-once caches — mapping resolution is expensive (maven
     *  fetches + tree composition) and queried repeatedly by wiring. */
    private val resolvedMappingData =
        mutableMapOf<String, xyz.wagyourtail.unimined.api.mapping.MappingData>()

    /** Reentrancy guard for [resolvedMappingData] — a resolving env's
     *  channel resolver can re-enter (e.g. `mojmap` reads `resolved()`
     *  auxiliary artifacts); the in-flight marker yields an empty data
     *  instead of deadlocking. */
    private val resolvingMappingData =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether [environmentId]'s mapping data is mid-resolution —
     *  callers short-circuit to a fallback rather than re-enter. */
    fun isResolving(environmentId: String): Boolean =
        environmentId in resolvingMappingData

    private val resolvedEnvironments = mutableMapOf<String, ProductArtifacts>()

    private val mappingBundleJsonCache =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    private val mappingExtension: MappingExtension
        get() = project.extensions.getByType(MappingExtension::class.java)

    private val mavenResolver = java.util.function.Function<String, Path> { notation ->
        resolveMavenNotation(notation)
    }

    internal fun resolvedMappingData(
        environment: MinecraftEnvironment
    ): xyz.wagyourtail.unimined.api.mapping.MappingData {
        resolvedMappingData[environment.environmentId]?.let { return it }
        if (!resolvingMappingData.add(environment.environmentId)) {
            return xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData(
                emptyMap()
            )
        }
        try {
            val t0 = System.nanoTime()
            environment.validateCombinations()
            val data = mappingExtension.resolve(
                environment.mappingsSpec, environment.environmentId,
                mavenResolver, channelResolver(environment),
                environment.side.get()
            )
            uniminedLogger(environment.environmentId, project.logger)
                .subLogger("mappings").lifecycle(
                    "resolved in ${(System.nanoTime() - t0) / 1_000_000}ms"
                )
            resolvedMappingData[environment.environmentId] = data
            return data
        } finally {
            resolvingMappingData.remove(environment.environmentId)
        }
    }

    internal fun mappingDataProvider(environment: MinecraftEnvironment): Provider<xyz.wagyourtail.unimined.api.mapping.MappingData> =
        providers.provider { resolvedMappingData(environment) }

    internal fun resolved(environment: MinecraftEnvironment): ProductArtifacts =
        synchronized(resolvedEnvironments) {
            resolvedEnvironments.getOrPut(environment.environmentId) {
                val spec = environment.spec()
                // loader-aware providers (buildtools claims
                // craftbukkit/spigot server artifacts) negotiate on the
                // full spec; when none claims it, loader-agnostic
                // providers see the stripped 1.x view (loader transforms
                // wrap the artifact downstream). An explicit
                // `provider =` override bypasses capability selection.
                val override = environment.provider.orNull
                val provider = when {
                    override != null -> ext.registry.select(spec, override)
                    else -> ext.registry.probe(spec)
                        // loader-agnostic 1.x view — transforms wrap
                        // the loader's artifact downstream
                        ?: ext.registry.select(
                            (spec as? RuntimeProductSpec)
                                ?.copy(loader = null) ?: spec
                        )
                }
                provider.resolve(spec, ext.providerContext)
            }
        }

    /**
     * Fetch the Spigot BuildData archive for [environment]'s mc
     * version into the global cache — used by the `spigot` mapping
     * channel when the provider doesn't supply `spigot_builddata`
     * (e.g. fabric/forge envs). Plain URL fetch honoring --offline
     * and --refresh-dependencies, per the project convention.
     */
    private fun spigotBuildData(environment: MinecraftEnvironment): Path {
        val mc = environment.version.get()
        val dir = UniminedCache.global(project).resolve("spigot/$mc")
        fun cached(name: String, url: String): Path {
            val dest = dir.resolve(name)
            val refresh = project.gradle.startParameter.isRefreshDependencies
            if (dest.toFile().exists() && !refresh) return dest
            if (project.gradle.startParameter.isOffline) {
                if (dest.toFile().exists()) return dest
                throw GradleException(
                    "spigot channel: --offline and no cached $name under $dir")
            }
            dest.toFile().parentFile.mkdirs()
            java.net.URI.create(url).toURL().openStream().use {
                java.nio.file.Files.copy(it, dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return dest
        }
        val info = cached("build-info.json",
            "https://hub.spigotmc.org/versions/$mc.json")
        val ref = Regex(""""BuildData"\s*:\s*"([0-9a-f]+)"""")
            .find(info.toFile().readText())?.groupValues?.get(1)
            ?: throw GradleException("spigot: no BuildData ref in $info")
        return cached("build-data.zip",
            "https://hub.spigotmc.org/stash/rest/api/latest/" +
                "projects/SPIGOT/repos/builddata/archive" +
                "?at=$ref&format=zip")
    }

    /**
     * Resolves builtin mapping channels for [environment] to local files —
     * `mojmap` from the provider's manifest downloads, `searge`/`parchment`
     * from Maven (registering the channel's repository when absent).
     */
    private fun channelResolver(environment: MinecraftEnvironment) =
        java.util.function.BiFunction<String, Map<String, String>, List<Path>> { channel, params ->
            when (channel) {
                "mojmap" -> {
                    val key = if (environment.side.get() == Side.SERVER) {
                        "server_mappings"
                    } else {
                        "client_mappings"
                    }
                    val file = resolved(environment).auxiliaryArtifacts[key]
                        ?.orNull
                        ?: throw GradleException(
                            "mappings { mojmap() }: provider " +
                                    "'${environment.provider.orNull ?: "auto"}' supplies no " +
                                    "$key channel for ${environment.version.orNull}"
                        )
                    listOf(file.asFile.toPath())
                }

                "searge" -> {
                    val version = params["version"]
                        ?: throw GradleException("searge() requires a version")
                    if (MinecraftVersions
                            .compare(version.substringBefore('-'), "1.12.2") < 0
                    ) {
                        throw GradleException(
                            "mappings { searge($version) }: FG1-era mcp/srg " +
                                    "channels are not yet supported; declare the " +
                                    "mapping archive via maven() or file() instead"
                        )
                    }
                    UniminedRepos(project).minecraftForge.ensure()
                    listOf(
                        resolveMavenNotation("de.oceanlabs.mcp:mcp_config:$version@zip")
                    )
                }

                "forgeBuiltinMCP" -> {
                    val version = params["version"]
                        ?: throw GradleException("forgeBuiltinMCP() requires a forge version")
                    val mcVersion = environment.version.get().let {
                        // 1.x forgeMCVersion: short mc versions map to
                        // their three-part form in forge coordinates
                        if (it == "1.4") "1.4.0" else if (it == "1.0") "1.0.0" else it
                    }
                    UniminedRepos(project).minecraftForge.ensure()
                    listOf(
                        resolveMavenNotation(
                            // FG1.2+ (1.7.10+) ships mappings in the userdev
                            // jar's conf/ dir; earlier FG1 ships them in the
                            // src zip's forge/fml/conf/
                            if (MinecraftVersions
                                    .compare(mcVersion, "1.7.10") >= 0
                            ) "net.minecraftforge:forge:$mcVersion-$version:userdev@jar"
                            else "net.minecraftforge:forge:$mcVersion-$version:src@zip"
                        )
                    )
                }

                "spigot" -> {
                    // BuildData archive — prefer the provider's
                    // spigot_builddata aux artifact (proper Downloader
                    // caching), else fetch it ourselves so the channel
                    // works on any env (e.g. a fabric server env that
                    // wants spigot member names for cursed reasons)
                    val supplied = resolved(environment)
                        .auxiliaryArtifacts["spigot_builddata"]
                        ?.get()?.asFile?.toPath()
                    listOf(supplied ?: spigotBuildData(environment))
                }

                "parchment" -> {
                    val mcVersion = params["mcVersion"]
                        ?: throw GradleException("parchment() requires mcVersion")
                    val version = params["version"]
                        ?: throw GradleException("parchment() requires a version")
                    val classifier =
                        if (params["checked"].toBoolean()) ":checked" else ""
                    UniminedRepos(project).parchment.ensure()
                    listOf(
                        resolveMavenNotation(
                            "org.parchmentmc.data:parchment-$mcVersion:$version$classifier@zip"
                        )
                    )
                }

                else -> throw GradleException(
                    "builtin mapping channel '$channel' is not supported by this host"
                )
            }
        }

    /** The dev namespace for [environmentId] — explicit
     *  `MinecraftEnvironment.devNamespace`, else the first *named*
     *  namespace the resolved sources flagged (1.x `outputs(ns, named)`
     *  semantics), else the composed tree's last namespace. Evaluated
     *  lazily. */
    internal fun devNamespace(environmentId: String): Provider<String> =
        ext.sourceSet(environmentId).devNamespace.orElse(providers.provider {
            val environment = ext.sourceSet(environmentId)
            if (environment.mappingsSpec.effectiveSources().isEmpty()) {
                ext.artifacts(environmentId).get().mappings.orNull?.namespaces?.lastOrNull()?.name
                    ?: throw GradleException(
                        "unimined: environment '$environmentId' produced no " +
                        "mapping tree — declare mappings { } or check the " +
                        "provider supports this version"
                    )
            } else {
                val data = mappingDataProvider(environment).orNull
                val tree = data?.view(MappingTree::class.java)
                if (tree == null) {
                    // declared-but-empty sources compose no tree —
                    // defer to the provider's mapping tree
                    ext.artifacts(environmentId).get().mappings.orNull?.namespaces?.lastOrNull()?.name
                        ?: throw GradleException(
                            "unimined: environment '$environmentId' produced no " +
                            "mapping tree — declare mappings { } or check the " +
                            "provider supports this version"
                        )
                } else {
                    val present = tree.namespaces.map { it.name }.toSet()
                    data.namedNamespaces().firstOrNull { it in present }
                        ?: tree.namespaces.last().name
                }
            }
        })

    /** The raw artifact's namespace for [environmentId] — the resolved
     *  mapping tree's first namespace. Evaluated lazily. */
    internal fun sourceNamespace(environmentId: String): Provider<String> =
        providers.provider {
            // split-side merged env: the produced artifact lives in the
            // shared merge namespace (each side was remapped into it
            // pre-merge), not the union official namespace
            if (ext.sourceSet(environmentId).mappingsSpec.splitOfficial) {
                modSourceNamespaces(environmentId).orNull?.singleOrNull()
                    ?: throw GradleException(
                        "unimined: environment '$environmentId' declares " +
                        "multiple or zero mod source namespaces — auto " +
                        "remapping supports exactly one; declare " +
                        "remap.remappedViews for mixed inputs"
                    )
            } else {
                ext.artifacts(environmentId).get().mappings.get()
                    .namespaces.first().name
            }
        }

    /** Effective mod-dependency source namespaces — the environment's
     *  explicit list, else the loader's contributed default, else the
     *  base (production) namespace for no-loader environments. An empty
     *  declaration counts as unset (list properties default to `[]`). */
    internal fun modSourceNamespaces(environmentId: String): Provider<List<String>> =
        providers.provider {
            val environment = ext.sourceSet(environmentId)
            environment.modSourceNamespaces.getOrElse(emptyList()).ifEmpty {
                // unobf'd products (26.x) dev == prod == official — the
                // loader's prod namespace (intermediary/mojmap) has no
                // channel and the remap is identity
                if (!environment.obfuscated.get()) listOf("mojmap")
                else ext.loaderModSourceNamespaces[environment.loaderSpec.orNull?.name]
                    ?: run {
                        // split-official merged envs merge in the spec's
                        // shared namespace — the side-official columns
                        // are per-side obf spaces, not mod sources
                        environment.mappingsSpec.splitMergeNamespace
                            ?.let { listOf(it) }
                            ?: listOf(
                                ext.artifacts(environmentId).get().mappings
                                    .get().namespaces.first().name
                            )
                    }
            }
        }

    /** Whether [environmentId]'s production namespace equals its dev
     *  namespace — mod artifacts need no remap between dev and
     *  production. Identity environments emit a single jar/coordinate
     *  for both development and production; the dev/prod split
     *  collapses. Evaluated lazily — may resolve the environment's
     *  mappings when the namespaces are not declared explicitly. */
    internal fun isIdentityRemap(environmentId: String): Provider<Boolean> =
        modSourceNamespaces(environmentId).zip(devNamespace(environmentId)) { prod, dev ->
            prod.size == 1 && prod.single() == dev
        }

    /** Resolve a `maven("g:a:v[:classifier]")` source lazily via a
     *  non-transitive detached configuration. */
    private fun resolveMavenNotation(notation: String): Path {
        UniminedRepos(project)
            .ensureForNotation(notation, project)
        return project.configurations.detachedConfiguration(
            project.dependencies.create(notation)
        ).apply { isTransitive = false }
            .files.also { files ->
                if (files.size != 1) throw GradleException(
                    "mappings { maven($notation) }: resolved ${files.size} " +
                    "files, expected exactly 1 — check the coordinate, " +
                    "the serving repository, and whether you are offline"
                )
            }.single().toPath()
    }

    /**
     * Register the mapping bundle task for one source-set environment.
     */
    internal fun mappingBundle(
        environmentId: String,
        from: String,
        to: String
    ): Provider<RegularFile> {
        val environment = ext.sourceSet(environmentId)
        val safeEnvironment = environmentId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val task = project.tasks.register(
            "produceMappingBundle_${safeEnvironment}_${from}_${to}",
            ProduceMappingBundleTask::class.java
        ) { t ->
            t.environmentId.set(environmentId)
            val mats = providers.provider {
                mappingExtension.materializeSources(
                    environment.mappingsSpec,
                    environmentId,
                    mavenResolver, channelResolver(environment),
                    environment.side.get()
                )
            }
            t.mappingFiles.from(mats.map { list ->
                list.flatMap { m -> m.files.map { it.toFile() } + m.attachments.values.map { it.toFile() } }
            })
            t.sourcesManifest.set(mats.map { list ->
                kotlinx.serialization.json.JsonArray(
                    list.map {
                        MaterializedSourceSpec.of(it).toJson()
                    }
                ).toString()
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
    internal fun mappingBundle(
        environmentId: String,
        nameSuffix: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<RegularFile> {
        val environment = ext.sourceSet(environmentId)
        val safeEnvironment = environmentId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val task = project.tasks.register(
            "produceMappingBundle_${safeEnvironment}_$nameSuffix",
            ProduceMappingBundleTask::class.java
        ) { t ->
            t.environmentId.set(environmentId)
            val mats = providers.provider {
                mappingExtension.materializeSources(
                    environment.mappingsSpec,
                    environmentId,
                    mavenResolver, channelResolver(environment),
                    environment.side.get()
                )
            }
            t.mappingFiles.from(mats.map { list ->
                list.flatMap { m -> m.files.map { it.toFile() } + m.attachments.values.map { it.toFile() } }
            })
            t.sourcesManifest.set(mats.map { list ->
                kotlinx.serialization.json.JsonArray(
                    list.map {
                        MaterializedSourceSpec.of(it).toJson()
                    }
                ).toString()
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
    internal fun mappingBundleJson(
        environmentId: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<String> {
        val environment = ext.sourceSet(environmentId)
        return providers.provider {
            // resolve once per environment (see resolvedMappingData) —
            // the transform param is evaluated per transform invocation,
            // and capture+serialize is itself worth memoizing per
            // (from, to) pair
            val bundleJson = mappingBundleJsonCache.computeIfAbsent(
                "$environmentId:${from.get()}:${to.get()}"
            ) {
                // the rich serialized form — row-only MappingBundle
                // drops mapping-element metadata (inner-class, generic
                // signature, exception enrichers) that the remapper's
                // metadata pass applies; "z64:" marks a base64 zip so
                // the transform can distinguish it from legacy bundles
                val data = resolvedMappingData(environment)
                // in-memory serialize — a temp file here becomes a
                // config-cache invalidation key (the path is fingerprinted
                // but deleted, so the entry can never be reused)
                SerializedMappingData.writeBytes(data)?.let {
                    "z64:" + java.util.Base64.getEncoder().encodeToString(it)
                } ?: run {
                    // no serializer covered the data's views —
                    // SerializedMappingData.write's contract says fall
                    // back to a row bundle: capture real rows when a
                    // MappingTree view exists, else emit a bare
                    // identity bundle — a view-less MappingData has
                    // nothing to rename (conversion envs' view-less
                    // official->official resolve)
                    val f = from.get(); val t = to.get()
                    data.view(MappingTree::class.java)?.let {
                        MappingBundle.capture(f, t, it).toJson()
                    } ?: MappingBundle(
                        listOf(f, t).distinct(), f, t,
                        emptyList()).toJson()
                }
            }
            bundleJson
        }
    }
}
