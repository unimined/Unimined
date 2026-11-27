package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.file.RegularFile
import xyz.wagyourtail.unimined.api.util.failProblem
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.produceCached
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.api.util.uniminedTools
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.env.InertProductArtifacts
import xyz.wagyourtail.unimined.mapping.MappingExtension
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.processors.BytecodeFillPostProcessor
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.mapping.internal.MaterializedSourceSpec
import xyz.wagyourtail.unimined.mapping.UniminedRepos
import xyz.wagyourtail.unimined.mapping.task.ProduceMappingBundleTask
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.util.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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

    private val mappingBundleFileCache =
        java.util.concurrent.ConcurrentHashMap<String, Path>()

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
            val spec = environment.mappingsSpec
            // bytecode hierarchy fill (ploceus MappingFiller parity) —
            // the env's raw jar + libraries feed a method-name
            // propagation pass over the composed tree. The spec flag is
            // tri-state: unset auto-enables when Ornithe enricher
            // sources are declared (ploceus's isProcessorsApplied gate)
            if (spec.hierarchyNameFill.orNull ?: spec.declaresOrnitheEnrichers()) {
                if (spec.postProcessors.none { it.name == "bytecode-fill" }) {
                    spec.postProcessors += BytecodeFillPostProcessor(
                        jars = bytecodeFillJars(environment),
                        libraries = {
                            project.configurations
                                .findByName(environment.minecraftLibrariesConfigurationName)
                                ?.files?.map { it.toPath() }
                                ?: emptyList()
                        }
                    )
                }
            }
            val data = mappingExtension.resolve(
                spec, environment.environmentId,
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
                if (!environment.version.isPresent) {
                    // versionless env — a source set that was never
                    // configured as a minecraft env (shared code pulled
                    // into real envs via combineWith, e.g. Omnilook's
                    // `main`). spec() would fail on the missing version,
                    // and there is no product to probe anyway — resolve
                    // inertly so the env behaves like the plain source
                    // set 1.x left it as.
                    return@getOrPut InertProductArtifacts(project)
                }
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
     * Product jar(s) feeding the bytecode hierarchy fill, keyed by the
     * tree namespace their raw class/member names live in: split-official
     * merged environments scan each side's jar under its own
     * `official:<side>` column (the per-side obf name sets are
     * disjoint); every other environment scans the side-appropriate
     * jar under `official`. Providers that cannot supply an artifact
     * contribute an empty list — the processor skips empty jars.
     * Provider auxiliary artifacts (e.g. the Realms jar) join the
     * `official` scan — they ride the raw-artifact bucket for remap
     * the same way. Jar paths resolve inside the lambda — execution
     * time only, never during DSL evaluation.
     */
    private fun bytecodeFillJars(
        environment: MinecraftEnvironment
    ): Map<String, () -> List<Path>> {
        fun RegularFile.toPathOrNull() = asFile.toPath()
        // product resolution defers into the jar lambdas — fill time is
        // execution time, and a spec without a resolvable product
        // (detached/versionless envs) contributes no jars so the fill
        // degrades to a no-op rather than failing the mapping resolve
        fun artifactsOrNull(): ProductArtifacts? =
            runCatching { resolved(environment) }
                .onFailure {
                    uniminedLogger(environment.environmentId, project.logger)
                        .subLogger("mappings").info(
                            "bytecode-fill: no resolvable product on " +
                                    "'${environment.environmentId}', skipping " +
                                    "the jar scan (${it.message})"
                        )
                }
                .getOrNull()
        fun artifact(vararg keys: ArtifactKey): () -> List<Path> = out@{
            val artifacts = artifactsOrNull() ?: return@out emptyList()
            val aux = artifacts.obfuscatedAuxArtifacts.orNull
                ?.mapNotNull { it.orNull?.toPathOrNull() }
                ?: emptyList()
            listOfNotNull(
                keys.firstNotNullOfOrNull { k -> artifacts.artifacts[k]?.orNull?.toPathOrNull() }
            ) + aux
        }
        return if (environment.mappingsSpec.splitOfficial &&
            environment.side.get() in setOf(Side.MERGED, Side.COMMON)) {
            mapOf(
                "official:client" to { listOfNotNull(
                    artifactsOrNull()?.artifacts?.get(ArtifactKey.CLIENT)
                        ?.orNull?.toPathOrNull()) },
                "official:server" to { listOfNotNull(
                    artifactsOrNull()?.artifacts?.get(ArtifactKey.SERVER)
                        ?.orNull?.toPathOrNull()) }
            )
        } else {
            mapOf("official" to when (environment.side.get()) {
                Side.CLIENT -> artifact(ArtifactKey.CLIENT, ArtifactKey.MERGED)
                Side.SERVER -> artifact(ArtifactKey.SERVER, ArtifactKey.MERGED)
                // prefer the intersection jar — a COMMON env's
                // `official` column only covers shared classes
                Side.COMMON -> artifact(
                    ArtifactKey.COMMON, ArtifactKey.MERGED,
                    ArtifactKey.CLIENT, ArtifactKey.SERVER
                )
                else -> artifact(ArtifactKey.MERGED, ArtifactKey.CLIENT, ArtifactKey.SERVER)
            })
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
                project.failProblem(
                    "mappings/spigot-cache",
                    "unimined: mappings { spigot() } — --offline and no " +
                        "cached $name under $dir")
            }
            dest.toFile().parentFile.mkdirs()
            // lock + temp+move — concurrent daemons must not serve a
            // partially-downloaded spigot artifact
            return dest.produceCached(force = true) { tmp ->
                java.net.URI.create(url).toURL().openStream().use {
                    java.nio.file.Files.copy(it, tmp)
                }
            }
        }
        val info = cached("build-info.json",
            "https://hub.spigotmc.org/versions/$mc.json")
        val ref = Regex(""""BuildData"\s*:\s*"([0-9a-f]+)"""")
            .find(info.toFile().readText())?.groupValues?.get(1)
            ?: project.failProblem("mappings/spigot-builddata",
                "unimined: mappings { spigot() } — no BuildData ref in " +
                    "$info")
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
                    // COMMON rides server_mappings — the intersection
                    // jar ships the server jar's bytes for shared
                    // classes (loom MinecraftJarSplitter parity), so
                    // server-side member obf names are what its
                    // bytecode actually carries
                    val key = if (environment.side.get() == Side.SERVER ||
                        environment.side.get() == Side.COMMON) {
                        "server_mappings"
                    } else {
                        "client_mappings"
                    }
                    val file = resolved(environment).auxiliaryArtifacts[key]
                        ?.orNull
                        ?: project.failProblem(
                            "mappings/mojmap",
                            "unimined: environment '${environment.name}' — " +
                                "mappings { mojmap() }: provider " +
                                "'${environment.provider.orNull ?: "auto"}' " +
                                "supplies no $key channel for " +
                                "${environment.version.orNull}"
                        )
                    listOf(file.asFile.toPath())
                }

                "searge" -> {
                    val version = params["version"]
                        ?: project.failProblem("mappings/searge-version",
                            "unimined: mappings { searge() } requires a " +
                                "version parameter")
                    UniminedRepos(project).minecraftForge.ensure()
                    listOf(
                        resolveMavenNotation(
                            // FG2-era (<1.12.2) predates MCPConfig —
                            // 1.x resolved the old mcp srg archive
                            // (joined.srg: official -> searge) instead
                            if (MinecraftVersions.compare(
                                    version.substringBefore('-'),
                                    "1.12.2") < 0
                            ) "de.oceanlabs.mcp:mcp:$version:srg@zip"
                            else "de.oceanlabs.mcp:mcp_config:$version@zip"
                        )
                    )
                }

                "forgeBuiltinMCP" -> {
                    val version = params["version"]
                        ?: project.failProblem(
                            "mappings/forge-builtin-mcp",
                            "unimined: mappings { forgeBuiltinMCP() } " +
                                "requires a forge version parameter")
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
                        ?: project.failProblem("mappings/parchment-mcversion",
                            "unimined: mappings { parchment() } requires " +
                                "an mcVersion parameter")
                    val version = params["version"]
                        ?: project.failProblem("mappings/parchment-version",
                            "unimined: mappings { parchment() } requires " +
                                "a version parameter")
                    val classifier =
                        if (params["checked"].toBoolean()) ":checked" else ""
                    UniminedRepos(project).parchment.ensure()
                    listOf(
                        resolveMavenNotation(
                            "org.parchmentmc.data:parchment-$mcVersion:$version$classifier@zip"
                        )
                    )
                }

                "yarnv1" -> {
                    // Tiny v1 yarn + stitch proposeFieldNames over the
                    // client jar (1.x yarnv1 semantics) — resolves the
                    // plain (non-v2) yarn artifact on the fabric maven
                    val mcVersion = params["version"]
                        ?: project.failProblem("mappings/yarnv1-version",
                            "unimined: mappings { yarnv1() } requires a " +
                                "version parameter")
                    val build = params["build"]
                        ?: project.failProblem("mappings/yarnv1-build",
                            "unimined: mappings { yarnv1() } requires a " +
                                "build parameter")
                    UniminedRepos(project).fabric.ensure()
                    val yarnJar = resolveMavenNotation(
                        "net.fabricmc:yarn:$mcVersion+build.$build"
                    )
                    val cacheDir = xyz.wagyourtail.unimined.api.util
                        .UniminedCache.global(project)
                        .resolve("yarnv1")
                    java.nio.file.Files.createDirectories(cacheDir)
                    val extracted = cacheDir.resolve(
                        "yarnv1-$mcVersion+build.$build.tiny")
                    extracted.produceCached { tmp ->
                        java.util.zip.ZipFile(yarnJar.toFile()).use { zip ->
                            val entry = zip.getEntry("mappings/mappings.tiny")
                                ?: project.failProblem(
                                    "mappings/yarnv1-tiny",
                                    "unimined: yarn jar $yarnJar carries no " +
                                    "mappings/mappings.tiny")
                            zip.getInputStream(entry).use { input ->
                                java.nio.file.Files.copy(input, tmp)
                            }
                        }
                    }
                    val filled = cacheDir.resolve(
                        "yarnv1-$mcVersion+build.$build-filled.tiny")
                    filled.produceCached { tmp ->
                        val stitch = resolveMavenNotation(
                            project.uniminedTools().stitch.get())
                        val arts = resolved(environment).artifacts
                        val mcJar = arts[xyz.wagyourtail.unimined
                            .minecraft.spi.ArtifactKey.CLIENT]?.orNull?.asFile
                            ?: arts[xyz.wagyourtail.unimined
                                .minecraft.spi.ArtifactKey.MERGED]
                                ?.orNull?.asFile
                            ?: project.failProblem(
                                "mappings/yarnv1-jar",
                                "unimined: environment '${environment.name}' — " +
                                "mappings { yarnv1() }: no client or merged " +
                                "jar for stitch proposeFieldNames")
                        val javaBin = java.nio.file.Paths.get(
                            System.getProperty("java.home"),
                            "bin", "java").toString()
                        val proc = ProcessBuilder(
                            javaBin, "-cp", stitch.toString(),
                            "net.fabricmc.stitch.Main",
                            "proposeFieldNames",
                            mcJar.absolutePath,
                            extracted.toAbsolutePath().toString(),
                            tmp.toAbsolutePath().toString()
                        ).redirectErrorStream(true).start()
                        val output = proc.inputStream.bufferedReader().readText()
                        val exit = proc.waitFor()
                        if (exit != 0 || !java.nio.file.Files.exists(tmp)) {
                            project.failProblem(
                                "mappings/yarnv1-stitch",
                                "unimined: mappings { yarnv1() }: stitch " +
                                "proposeFieldNames failed (exit $exit)",
                                output.takeLast(4000))
                        }
                    }
                    listOf(filled)
                }

                else -> project.failProblem(
                    "mappings/channel-unsupported",
                    "unimined: builtin mapping channel '$channel' is not " +
                        "supported by this host"
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
                    ?: project.failProblem(
                        "env/mapping-tree",
                        "unimined: environment '$environmentId' produced no " +
                        "mapping tree",
                        "declare mappings { } or check the provider " +
                        "supports this version"
                    )
            } else {
                val data = mappingDataProvider(environment).orNull
                val tree = data?.view(MappingTree::class.java)
                if (tree == null) {
                    // declared-but-empty sources compose no tree —
                    // defer to the provider's mapping tree
                    ext.artifacts(environmentId).get().mappings.orNull?.namespaces?.lastOrNull()?.name
                        ?: project.failProblem(
                            "env/mapping-tree",
                            "unimined: environment '$environmentId' produced " +
                            "no mapping tree",
                            "declare mappings { } or check the provider " +
                            "supports this version"
                        )
                } else {
                    val present = tree.namespaces.map { it.name }.toSet()
                    data.namedNamespaces().firstOrNull { it in present }
                        ?: tree.namespaces.last().name
                }
            }
        })

    /** The resolved named (developer-facing) namespaces for
     *  [environmentId] — the source-declared flag set
     *  (`MappingData.namedNamespaces`), filtered to columns present in
     *  the composed tree. Empty when nothing is flagged — the dev
     *  namespace may still resolve to an unflagged last column, so
     *  callers needing "the named role" should union devNamespace.
     *  Evaluated lazily. */
    internal fun namedNamespaces(environmentId: String): Provider<Set<String>> =
        providers.provider {
            val environment = ext.sourceSet(environmentId)
            val data = mappingDataProvider(environment).orNull
                ?: return@provider emptySet()
            val tree = data.view(MappingTree::class.java)
                ?: return@provider emptySet()
            val present = tree.namespaces.mapTo(HashSet()) { it.name }
            data.namedNamespaces().filterTo(LinkedHashSet()) { it in present }
        }

    /** The env tree's obf/production column — the raw jar's namespace.
     *  `namespaces.first()` is unreliable: retroMCP-era source files
     *  order their *named* column first, which would return the dev
     *  namespace and collapse the raw->dev remap to identity. Prefer
     *  the canonical `official` column (envView folds a sided env's
     *  `official:<side>` to it), else the first column not flagged
     *  named, else column 0. */
    private fun jarSourceNamespace(
        tree: MappingTree, named: Set<String>
    ): String =
        (tree.namespaces.firstOrNull { it.name == "official" }
            ?: tree.namespaces.firstOrNull { it.name !in named }
            ?: tree.namespaces.first()).name

    /** The raw artifact's namespace for [environmentId] — the resolved
     *  mapping tree's jar-side (obf/production) namespace. Evaluated
     *  lazily. */
    internal fun sourceNamespace(environmentId: String): Provider<String> =
        providers.provider {
            val environment = ext.sourceSet(environmentId)
            val tree = ext.artifacts(environmentId).get().mappings.get()
            if (environment.mappingsSpec.splitOfficial) {
                // split-side merged env: the produced artifact lives in
                // the shared merge namespace (each side was remapped into
                // it pre-merge — the declared mod-source column), not the
                // union official namespace
                modSourceNamespaces(environmentId).orNull
                    ?.singleOrNull()
                    ?: project.failProblem(
                        "env/mod-source-namespaces",
                        "unimined: environment '$environmentId' declares " +
                        "multiple or zero mod source namespaces",
                        "auto remapping supports exactly one; declare " +
                        "remap.remappedViews for mixed inputs"
                    )
            } else {
                // non-merged envs read the produced jar's own column
                // (official-obf ornithe/forge jars, intermediary fabric
                // jars...) — the declared mod-source column may be a
                // loader-only hop unrelated to jar content (calamusGen2)
                jarSourceNamespace(
                    tree,
                    namedNamespaces(environmentId).orNull ?: emptySet()
                )
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
                                jarSourceNamespace(
                                    ext.artifacts(environmentId).get()
                                        .mappings.get(),
                                    namedNamespaces(environmentId).orNull
                                        ?: emptySet()
                                )
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
                if (files.size != 1) project.failProblem(
                    "mappings/maven-notation",
                    "unimined: mappings { maven($notation) }: resolved " +
                    "${files.size} files, expected exactly 1",
                    "check the coordinate, the serving repository, and " +
                    "whether you are offline"
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
            t.environmentSide.set(environment.side)
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
            t.environmentSide.set(environment.side)
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

    /**
     * File-backed variant of [mappingBundleJson] — the same payload
     * (rich `SerializedMappingData` zip, or the row-bundle fallback
     * when no serializer covers the data's views) written once under
     * the global cache's `mappings-bundle/`, content-addressed by
     * sha1. Transform params carry the file reference instead of an
     * inline `z64:` megastring, so the serialized tree stops riding
     * the configuration-cache payload and param serialization once
     * per registered transform. `SerializedMappingData.readEither`
     * sniffs both payload kinds — identical semantics, transport
     * aside.
     */
    internal fun mappingBundleFile(
        environmentId: String,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<RegularFile> {
        val environment = ext.sourceSet(environmentId)
        return project.layout.file(providers.provider {
            val path = mappingBundleFileCache.computeIfAbsent(
                "$environmentId:${from.get()}:${to.get()}"
            ) {
                val data = resolvedMappingData(environment)
                val bytes = SerializedMappingData.writeBytes(data)
                    ?: run {
                        val f = from.get(); val t = to.get()
                        (data.view(MappingTree::class.java)?.let {
                            MappingBundle.capture(f, t, it).toJson()
                        } ?: MappingBundle(
                            listOf(f, t).distinct(), f, t, emptyList()
                        ).toJson()).toByteArray(Charsets.UTF_8)
                    }
                val sha = MessageDigest.getInstance("SHA-1")
                    .digest(bytes).toHex()
                UniminedCache.global(project)
                    .resolve("mappings-bundle").resolve("$sha.bundle").also {
                        if (!Files.exists(it)) {
                            Files.createDirectories(it.parent)
                            it.writeAtomically { tmp -> Files.write(tmp, bytes) }
                        }
                    }
            }
            path.toFile()
        })
    }
}

/** Ornithe enricher artifact names that auto-enable the bytecode
 *  hierarchy fill — ploceus's `isProcessorsApplied` gate checks for
 *  nests/sparrow/raven providers on the classpath. */
private val ORNITHE_ENRICHER_ARTIFACTS = setOf("nests", "sparrow", "raven")

/** Whether this spec declares an Ornithe enricher (`nests`/`sparrow`/
 *  `raven`) mapping source — the auto-enable signal for `hierarchyNameFill`
 *  when the flag is unset. Reads only the declared coordinate
 *  notation, so no maven resolution is triggered. */
internal fun MappingsSpecImpl.declaresOrnitheEnrichers(): Boolean =
    effectiveSources().any { src ->
        src is MappingsSpecImpl.Source.Maven &&
            runCatching { src.notation }.getOrNull()
                ?.substringAfter(':', "")
                ?.substringBefore(':')
                ?.let { it in ORNITHE_ENRICHER_ARTIFACTS } == true
    }
