package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.api.spec.Side

/**
 * The `mappings { }` block state: the declared namespace chain plus the
 * ordered list of mapping sources feeding it.
 *
 * @param mcVersion the owning environment's Minecraft version — used by
 *  channel sugar such as [yarn] to default the artifact version; may be
 *  null on detached spec instances
 * @param side the owning environment's side — Ornithe gen-1 artifacts
 *  are published per game side (`-client`/`-server` suffixes); may be
 *  null on detached spec instances
 */
class MappingsSpecImpl(
    objects: ObjectFactory,
    private val mcVersion: Provider<String>? = null,
    envSide: Provider<Side>? = null
) : MappingsSpec {

    /** The spec's side — the owning env's side, or [Side.MERGED]
     *  when none applies (standalone specs). Exposed so migrate
     *  `target { side.set(...) }` can constrain the target spec to
     *  one env column — e.g. a gen1 target resolving only the client
     *  artifact instead of both. */
    val side: Property<Side> = objects.property(Side::class.java).apply {
        if (envSide != null) convention(envSide) else convention(Side.MERGED)
    }

    private val fileCollectionFactory = objects

    /** Mapping sources declared directly on this environment, in chain order. */
    val sources = mutableListOf<Source>()

    /**
     * Sources prepended to every effective chain — contributed by
     * transform plugins rather than the user (e.g. fabric's
     * `official -> intermediary` hop). They always compose ahead of
     * declared and inherited sources.
     */
    val defaultSources = mutableListOf<Source>()

    /**
     * Spec-scoped post-processors appended by transform wiring — e.g. a
     * jar processor's mapping mutation (mod-provided javadoc). They run
     * after the registry's ServiceLoader'd/registered processors when
     * this spec resolves; held as instances, never serialized.
     */
    val postProcessors = mutableListOf<MappingPostProcessor>()

    /**
     * Runs [block] against this spec (channel DSL calls like [calamus]),
     * then moves the appended [sources] entries out and returns them —
     * lets transform wiring route channel-produced sources into
     * [defaultSources] so loader-contributed hops still compose ahead
     * of user declarations.
     */
    fun collectDeclared(block: MappingsSpecImpl.() -> Unit): List<Source> {
        val start = sources.size
        block()
        return sources.drop(start).also { sources.subList(start, sources.size).clear() }
    }

    /**
     * Lazily-evaluated default source contributions — transform wiring
     * registers these at bound time, but the descriptor state they read
     * (loader, version) isn't populated until the environment's DSL
     * runs, so they're invoked per [effectiveSources] call. Each lambda
     * typically wraps [collectDeclared].
     */
    val deferredDefaultSources =
        mutableListOf<MappingsSpecImpl.() -> List<Source>>()

    private var inheritedSources: ((MutableSet<MappingsSpecImpl>) -> List<Source>)? = null

    /**
     * Ornithe mapping generation — `1` uses the original
     * `calamus-intermediary`/`feather` artifacts on the `calamus`
     * namespace; `>=2` selects the `-gen<N>` generated series on a
     * separate `calamusGen<N>`/`featherGen<N>` namespace so both gens
     * can be imported at once (per-call `gen` params override this).
     */
    var ornitheGenVersion: Int = 1

    /**
     * Legacy Fabric mapping generation — `>=2` selects the
     * `net.legacyfabric.v<N>` group on `legacyIntermediaryGen<N>`/
     * `legacyYarnGen<N>` namespaces, same both-gens model as
     * [ornitheGenVersion].
     */
    var legacyFabricGenVersion: Int = 1

    /** Namespace chain — populated by the mapping host at resolve time. */
    override var namespaces: List<String> = emptyList()
        internal set

    /** Declare a local mapping file source. */
    @JvmOverloads
    fun file(path: Any, action: Source.() -> Unit = {}) {
        sources += Source.File(fileCollectionFactory.fileCollection().from(path))
            .apply(action)
    }

    /** Declare a maven-coordinate mapping source — resolved lazily. */
    @JvmOverloads
    fun maven(notation: String, action: Source.() -> Unit = {}) {
        maven(fileCollectionFactory.property(String::class.java).apply { set(notation) }, action)
    }

    /** Declare a maven-coordinate mapping source from a provider — the
     *  coordinate resolves at materialization time. */
    @JvmOverloads
    fun maven(notation: Provider<String>, action: Source.() -> Unit = {}) {
        sources += Source.Maven(notation).apply(action)
    }

    /** Declare a provider builtin channel such as `official()`. */
    @JvmOverloads
    fun builtin(
        name: String,
        params: Map<String, Provider<String>> = emptyMap(),
        action: Source.() -> Unit = {}
    ) {
        sources += Source.Builtin(const(name), params).apply(action)
    }

    private fun const(value: String): Provider<String> =
        fileCollectionFactory.property(String::class.java).apply { set(value) }

    private fun requireMcVersion(channel: String): Provider<String> =
        requireNotNull(mcVersion) {
            "$channel() requires the environment's Minecraft version to be set"
        }

    /**
     * Ornithe artifact side suffix — gen-1 coordinates are published
     * per side (`-client`/`-server`), gen >= 2 publishes merged
     * artifacts with no suffix. A merged environment on a gen-1 chain
     * has no artifact to resolve (1.x fails the same case).
     */
    /**
     * Ornithe artifact side suffixes — gen-1 coordinates are published
     * per side (`-client`/`-server`); a merged environment emits ONE
     * declaration per side so the composer unions both official
     * columns into a single `official` namespace (per-side obf name
     * sets are disjoint on split-era versions, 1.x parity). gen >= 2
     * publishes merged artifacts — single suffix.
     */
    /**
     * Whether this spec composed per-side official source columns —
     * set when channels emit dual client+server declarations on a
     * merged environment (split-era versions: client/server obf
     * namespaces are disjoint). The environment artifact transform
     * reads this to drive the remap-before-merge path: each side's
     * jar remaps from [splitOfficialNamespaces]' column into the
     * shared namespace before the member-level merge.
     */
    var splitOfficial: Boolean = false
        private set

    /** Per-side official column names on a split-official merged
     *  environment — `[official:client, official:server]`. */
    var splitOfficialNamespaces: List<String> = emptyList()
        private set

    /** The shared namespace both sides remap into before merging —
     *  the column the per-side official columns map onto (e.g.
     *  `calamus`). Set by [calamus] when it declares the merge hop. */
    var splitMergeNamespace: String? = null
        private set

    /** Split-obfuscation era — Minecraft versions before 1.3
     *  (incl. all beta/alpha) have distinct client/server obf
     *  namespaces; 1.3+ merged the jars. Same test as 1.x
     *  `splitUnmapped`. Unknown/blank versions default to true —
     *  emitting both env columns is a superset non-split
     *  consumers safely ignore, while omitting the server column
     *  breaks split-era bridging. */
    private fun isSplitEra(): Boolean {
        val mc = mcVersion?.orNull?.substringBefore('-')
            ?: return true
        if (mc.isBlank()) return true
        return try {
            xyz.wagyourtail.unimined.mapping.internal
                .MinecraftVersions.compare(mc, "1.3") < 0
        } catch (e: IllegalStateException) {
            // uncomparable scheme (beta/alpha ids like b1.7.3) —
            // those eras are all pre-1.3, hence split
            true
        }
    }

    private fun ornitheSideSuffixes(
        channel: String, gen: Int
    ): List<Pair<Provider<String>, String>> {
        if (gen >= 2) return listOf(const("") to "official")
        val s = side.get()
        val splitEra = isSplitEra()
        if (s == Side.MERGED && splitEra) {
            splitOfficial = true
            splitOfficialNamespaces = listOf("official:client", "official:server")
        }
        return when {
            s == Side.CLIENT -> listOf(const("-client") to "official")
            s == Side.SERVER -> listOf(const("-server") to "official")
            // non-split merged versions ship one merged artifact
            s == Side.MERGED && !splitEra ->
                listOf(const("") to "official")
            else -> listOf(
                const("-client") to "official:client",
                const("-server") to "official:server"
            )
        }
    }

    /**
     * Declare Fabric's `intermediary` channel for the environment's
     * Minecraft version — `net.fabricmc:intermediary:<mcVersion>:v2`.
     * Fabric loaders contribute this hop implicitly; declare it
     * explicitly on chains where a source's `intermediary -> named`
     * hop needs `intermediary` present to stitch onto (yarn on a
     * forge/searge chain — 1.x `yarn()` `requires("intermediary")`).
     */
    fun intermediary() = maven(
        requireMcVersion("intermediary")
            .map { "net.fabricmc:intermediary:$it:v2" }
    )

    /**
     * Declare Yarn named mappings for the environment's Minecraft version —
     * `yarn(10)` ⇢ `net.fabricmc:yarn:<mcVersion>+build.10:v2`.
     */
    fun yarn(build: Int) = maven(
        requireNotNull(mcVersion) {
            "yarn(build) requires the environment's Minecraft version to be set"
        }.map { "net.fabricmc:yarn:$it+build.$build:v2" }
    )

    /** Declare Mojang's official runtime mappings channel. */
    fun official() = builtin("official")

    /** Declare the Mojmap built-in mappings channel. */
    fun mojmap() = builtin("mojmap")

    /**
     * Declare MCP mappings — `mcp("stable", "39-1.12")` ⇢
     * `de.oceanlabs.mcp:mcp_stable:39-1.12@zip` on the Forge maven
     * (`channel == "legacy"` resolves on wagyourtail's instead).
     * The csvs emit `searge`→`mcp`, so pair with [searge] for the
     * `official` hop; `mcp` is flagged as the named namespace.
     */
    fun mcp(channel: String, version: String) = maven(
        "de.oceanlabs.mcp:mcp_$channel:$version@zip"
    ) {
        named("mcp")
        // 1.x per-format relabels, applied source-wide: srg-family docs
        // inside the zip label the obf side `source`/`target`, v3-era
        // csvs label it `notch` — the shipped jar is `official`-named
        mapNamespace("source" to "official", "target" to "searge")
        mapNamespace("notch" to "official")
    }

    /**
     * Declare RetroMCP mappings for pre-1.3-era Minecraft —
     * `retroMCP("v1.0")` ⇢ `io.github.mcphackers:mcp:<version>@zip`
     * on the MCPHackers ivy; `retroMCP` is flagged as the named
     * namespace (the document's own `named` label is relabeled).
     */
    fun retroMCP(version: String) = maven(
        const(version).map {
            "io.github.mcphackers:mcp:${retroMCPArtifactKey(it)}@zip"
        }
    ) {
        mapNamespace("named" to "retroMCP")
        named("retroMCP")
        retroMCPOfficialRelabel()
    }

    /**
     * Declare Ornithe Feather mappings for the environment's Minecraft
     * version — `feather(4)` ⇢ `net.ornithemc:feather:<mc>+build.4:v2`
     * on the Ornithe maven; rides the `intermediary` hop.
     */
    @JvmOverloads
    fun feather(build: Int, gen: Int = ornitheGenVersion) {
        for ((sfx, _) in ornitheSideSuffixes("feather", gen)) {
            maven(
                requireMcVersion("feather").zip(sfx) { mc, s ->
                    val artifact = if (gen < 2) "feather" else "feather-gen$gen"
                    "net.ornithemc:$artifact:$mc$s+build.$build:v2"
                }
            ) {
                featherConfig(gen)
            }
        }
    }

    private fun Source.featherConfig(gen: Int) {
        // the file's src column is labeled `intermediary` in every gen;
        // relabel to the gen-keyed calamus namespace so the hop stitches
        mapNamespace(
            "intermediary" to if (gen < 2) "calamus" else "calamusGen$gen",
            "named" to if (gen < 2) "feather" else "featherGen$gen"
        )
        // the `named`-column convention only catches the literal name —
        // flag the relabeled target so it wins dev-namespace selection
        named(if (gen < 2) "feather" else "featherGen$gen")
    }

    /**
     * Ornithe enricher source — `nests`/`sparrow`/`raven` artifacts
     * carry metadata-only files (inner-class attributes, generic
     * signatures, exception mappings) keyed by per-side official names.
     * UML reads all three formats; the elements apply during remap
     * (nests restore InnerClass attributes for decompilation).
     */
    @JvmOverloads
    fun nests(build: Int) =
        ornitheEnricher("nests", "nests", build)

    /** Ornithe sparrow generic-signature enricher — see [nests]. */
    @JvmOverloads
    fun sparrow(build: Int) =
        ornitheEnricher("signatures", "sparrow", build)

    /** Ornithe raven exception-mapping enricher — see [nests]. */
    @JvmOverloads
    fun raven(build: Int) =
        ornitheEnricher("exceptions", "raven", build)

    private fun ornitheEnricher(
        channel: String, artifact: String, build: Int
    ) {
        // enrichers publish a single series keyed on official names —
        // always the sided-artifact path; they graft onto the env's
        // official:<side> columns regardless of calamus/feather gen
        val suffixes = ornitheSideSuffixes(channel, 1)
        for ((sfx, officialNs) in suffixes) {
            maven(
                requireMcVersion(channel).zip(sfx) { mc, s ->
                    "net.ornithemc:$artifact:$mc$s+build.$build"
                }
            ) {
                // enricher files label their key namespace `source`;
                // relabel to the side's official column — merged
                // split-official envs keep per-side obf namespaces
                // disjoint (1.x model)
                mapNamespace("source" to officialNs)
            }
        }
    }

    /**
     * Declare Legacy Fabric Yarn for the environment's Minecraft
     * version — `legacyYarn(510)` ⇢
     * `net.legacyfabric:yarn:<mc>+build.510:v2` on the Legacy Fabric
     * maven; rides the `intermediary` hop.
     */
    @JvmOverloads
    fun legacyYarn(build: Int, gen: Int = legacyFabricGenVersion) = maven(
        requireMcVersion("legacyYarn").map {
            val group = if (gen < 2) "net.legacyfabric" else "net.legacyfabric.v$gen"
            "$group:yarn:$it+build.$build:v2"
        }
    ) {
        if (gen >= 2) mapNamespace("named" to "legacyYarnGen$gen")
    }

    /**
     * Declare Babric Barn mappings for the environment's Minecraft
     * version — `barn(17)` ⇢ `babric:barn:<mc>+build.17:v2` on the
     * Glass Launcher babric maven; rides the `babricIntermediary` hop.
     */
    fun barn(build: Int) = maven(
        requireMcVersion("barn").map { "babric:barn:$it+build.$build:v2" }
    )

    /**
     * Declare Nostalgia mappings — `nostalgia(<build>)` ⇢
     * `me.alphamode:nostalgia:<mcVersion>+build.<N>:v2` on the Wisp
     * Forest maven; babric-family named layer riding
     * `babricIntermediary` (requires that hop to be present).
     */
    fun nostalgia(build: Int) = maven(
        requireMcVersion("nostalgia").map { "me.alphamode:nostalgia:$it+build.$build:v2" }
    ) {
        mapNamespace("intermediary" to "babricIntermediary")
    }

    /** [String]-build overload of [nostalgia]. */
    fun nostalgia(build: String) = maven(
        requireMcVersion("nostalgia").map { "me.alphamode:nostalgia:$it+build.$build:v2" }
    ) {
        mapNamespace("intermediary" to "babricIntermediary")
    }

    /**
     * Declare Unascribed "unknownthingy" mappings —
     * `unknownThingy("<version>", "<format>")` resolves
     * `com.unascribed:unknownthingy:<version>` with a per-format
     * classifier/extension: `tiny`/`tinyv2` → `:v2` classifier,
     * `tsrg2` → `@tsrg2`, anything else → `@tsrg`. Namespace
     * relabels follow the format (tiny `named`, tsrg2 `srg`,
     * tsrg `target` → `unknownThingy`).
     */
    @JvmOverloads
    fun unknownThingy(version: String, format: String = "tsrg") = maven(
        when (format.lowercase()) {
            "tiny", "tinyv1", "tinyv2" -> "com.unascribed:unknownthingy:$version:v2"
            "tsrg2" -> "com.unascribed:unknownthingy:$version@tsrg2"
            else -> "com.unascribed:unknownthingy:$version@tsrg"
        }
    ) {
        when (format.lowercase()) {
            "tiny", "tinyv1", "tinyv2" -> mapNamespace("named" to "unknownThingy")
            "tsrg2" -> mapNamespace("obf" to "official", "srg" to "unknownThingy")
            else -> mapNamespace("source" to "official", "target" to "unknownThingy")
        }
    }

    /**
     * Declare Quilt mappings for the environment's Minecraft version —
     * `quilt(12)` ⇢ `org.quiltmc:quilt-mappings:<mc>+build.12:intermediary-v2`
     * on the Quilt maven; rides the `intermediary` hop.
     */
    fun quilt(build: Int) = maven(
        requireMcVersion("quilt").map { "org.quiltmc:quilt-mappings:$it+build.$build:intermediary-v2" }
    )

    /**
     * Declare Ornithe Calamus intermediary —
     * `net.ornithemc:calamus-intermediary:<mcVersion>:v2` on the Ornithe
     * maven; the `intermediary` column is relabeled `calamus` (1.x
     * `provides("calamus" to false) + mapNamespace`).
     */
    @JvmOverloads
    fun calamus(gen: Int = ornitheGenVersion) {
        for ((sfx, officialNs) in ornitheSideSuffixes("calamus", gen)) {
            maven(
                requireMcVersion("calamus").zip(sfx) { mc, s ->
                    val artifact =
                        if (gen < 2) "calamus-intermediary" else "calamus-intermediary-gen$gen"
                    "net.ornithemc:$artifact:$mc$s:v2"
                }
            ) {
                mapNamespace("intermediary" to if (gen < 2) "calamus" else "calamusGen$gen")
                // the calamus hop target is the shared merge
                // namespace — both per-side officials map onto it
                if (officialNs != "official") {
                    splitMergeNamespace =
                        if (gen < 2) "calamus" else "calamusGen$gen"
                }
                // gen-1 files label their src column `official` —
                // on merged split-official envs each side's obf ns is
                // distinct (same-named client/server classes differ),
                // so relabel per side via the file-env tag, not into a
                // union column
                if (officialNs != "official") {
                    env(officialNs.substringAfter(':'))
                }
                if (gen >= 2) {
                    // gen>=2 files carry split clientOfficial/serverOfficial
                    // dst columns; a sided env only needs its own side,
                    // relabeled into its env column (`official:client`) —
                    // envView(side) then sees it as plain `official` while
                    // joined stays exactly `official`. MERGED needs the
                    // same file declared twice (per-column union) —
                    // not yet supported on gen >= 2
                    val s = side.get()
                    when {
                        s == Side.CLIENT -> mapNamespace("clientOfficial" to "official:client")
                        s == Side.SERVER -> mapNamespace("serverOfficial" to "official:server")
                        // the one file carries both per-side official
                        // columns — relabel both, and flag the
                        // split-official merge the same as gen-1:
                        // each side remaps through calamusGen2, then
                        // the jar merge pairs by it
                        s == Side.MERGED && isSplitEra() -> {
                            mapNamespace("clientOfficial" to "official:client")
                            mapNamespace("serverOfficial" to "official:server")
                            splitOfficial = true
                            splitOfficialNamespaces =
                                listOf("official:client", "official:server")
                            splitMergeNamespace = "calamusGen$gen"
                        }
                        // non-split merged env: one obf space —
                        // official is just the client column
                        s == Side.MERGED ->
                            mapNamespace("clientOfficial" to "official")
                        // no env (migration target, ad-hoc specs) —
                        // relabel both so `official:client`/`official:server`
                        // are available as graft/bridge columns
                        else -> {
                            mapNamespace("clientOfficial" to "official:client")
                            mapNamespace("serverOfficial" to "official:server")
                        }
                    }
                }
            }
        }
    }

    /**
     * Declare Legacy Fabric intermediary —
     * `net.legacyfabric:intermediary:<mcVersion>:v2`; relabeled
     * `legacyIntermediary` like [calamus].
     */
    @JvmOverloads
    fun legacyIntermediary(gen: Int = legacyFabricGenVersion) = maven(
        requireMcVersion("legacyIntermediary").map {
            val group = if (gen < 2) "net.legacyfabric" else "net.legacyfabric.v$gen"
            "$group:intermediary:$it:v2"
        }
    ) {
        mapNamespace("intermediary" to
            if (gen < 2) "legacyIntermediary" else "legacyIntermediaryGen$gen")
    }

    /**
     * Declare Babric upstream intermediary —
     * `babric:intermediary-upstream:<mcVersion>:v2` on the Glass
     * Launcher maven; the `barn`/`biny` hops ride this namespace.
     */
    fun babricIntermediary() = maven(
        requireMcVersion("babricIntermediary").map { "babric:intermediary-upstream:$it:v2" }
    ) {
        mapNamespace("intermediary" to "babricIntermediary")
    }

    /**
     * Declare Biny mappings — `biny("<commitName>")` ⇢
     * `net.glasslauncher:biny:<mcVersion>+<commitName>:v2`; babric-
     * family named layer on `babricIntermediary`.
     */
    fun biny(commitName: String) = maven(
        requireMcVersion("biny").map { "net.glasslauncher:biny:$it+$commitName:v2" }
    )

    /** String-build overloads — build numbers accept non-numeric
     *  forms (e.g. `"latest"` resolved by the maven side). */
    fun yarn(build: String) = maven(
        requireMcVersion("yarn").map { "net.fabricmc:yarn:$it+build.$build:v2" }
    )
    /** [String]-build overload of [feather]. */
    fun feather(build: String) = maven(
        requireMcVersion("feather").map { "net.ornithemc:feather:$it+build.$build:v2" }
    )
    /** [String]-build overload of [legacyYarn]. */
    fun legacyYarn(build: String) = maven(
        requireMcVersion("legacyYarn").map { "net.legacyfabric:yarn:$it+build.$build:v2" }
    )
    /** [String]-build overload of [barn]. */
    fun barn(build: String) = maven(
        requireMcVersion("barn").map { "babric:barn:$it+build.$build:v2" }
    )
    /** [String]-build overload of [quilt]. */
    fun quilt(build: String) = maven(
        requireMcVersion("quilt").map { "org.quiltmc:quilt-mappings:$it+build.$build:intermediary-v2" }
    )

    /** Declare RetroMCP at the environment's Minecraft version. */
    fun retroMCP() = maven(
        requireMcVersion("retroMCP").map {
            "io.github.mcphackers:mcp:${retroMCPArtifactKey(it)}@zip"
        }
    ) {
        mapNamespace("named" to "retroMCP")
        named("retroMCP")
        retroMCPOfficialRelabel()
    }

    /**
     * retroMCP files carry split `client`/`server` dst columns —
     * per-side official names (same concept as gen-2
     * `clientOfficial`/`serverOfficial`). Sided envs keep their own
     * side's column relabeled into `official:<side>`; side-less specs
     * (migration targets) and MERGED get both — the merged env's
     * compose path unions the side columns client-wins.
     */
    private fun Source.retroMCPOfficialRelabel() {
        when (side.get()) {
            Side.CLIENT -> mapNamespace("client" to "official:client")
            Side.SERVER -> mapNamespace("server" to "official:server")
            else -> {
                if (isSplitEra()) {
                    mapNamespace("client" to "official:client")
                    mapNamespace("server" to "official:server")
                } else {
                    // non-split merged env — one obf space
                    mapNamespace("client" to "official")
                }
            }
        }
    }

    /**
     * Declare the Searge (MCPConfig) mappings channel — [version] is the
     * MCPConfig artifact version; it defaults to the environment's
     * Minecraft version.
     */
    fun searge(version: String) =
        builtin("searge", mapOf("version" to const(version)))

    /** Declare Searge mappings at the environment's Minecraft version. */
    fun searge() =
        builtin("searge", mapOf("version" to requireMcVersion("searge")))

    /**
     * Declare the MCP data bundled inside an FG1-era forge `:src` zip —
     * `net.minecraftforge:forge:<mcVersion>-<version>:src@zip`. The zip's
     * `joined.srg` provides the `official -> searge` hop; its csvs layer
     * `searge -> forgeMCP` member/package/param names (`forgeMCP` is the
     * named namespace). [version] is the forge build, e.g. `6.6.2.534`
     * for `1.4.7-6.6.2.534`. FG1 environments wire this automatically
     * unless [customSearge]-style opt-out is declared on the env.
     */
    fun forgeBuiltinMCP(version: String) =
        builtin(
            "forgeBuiltinMCP",
            mapOf("version" to const(version))
        ) { named("forgeMCP") }

    /**
     * Declare Parchment parameter/javadoc mappings layered on `mojmap`.
     * [mcVersion] selects the `parchment-<mcVersion>` artifact family and
     * defaults to the environment's Minecraft version; [checked] selects
     * the `-checked` classifier build.
     */
    @JvmOverloads
    fun parchment(mcVersion: String, version: String, checked: Boolean = false) =
        builtin(
            "parchment",
            mapOf(
                "mcVersion" to const(mcVersion),
                "version" to const(version),
                "checked" to const(checked.toString())
            )
        )

    /** Declare Parchment mappings against the environment's Minecraft version. */
    @JvmOverloads
    fun parchment(version: String, checked: Boolean = false) =
        builtin(
            "parchment",
            mapOf(
                "mcVersion" to requireMcVersion("parchment"),
                "version" to const(version),
                "checked" to const(checked.toString())
            )
        )

    /**
     * Use another environment's mappings when this environment declares none.
     * A direct declaration always wins, preserving
     * `copyFrom(...); mappings { ... }` override ordering.
     */
    fun inheritSources(provider: (MutableSet<MappingsSpecImpl>) -> List<Source>) {
        inheritedSources = provider
    }

    /** Mapping declarations after applying defaults and environment inheritance. */
    @JvmOverloads
    fun effectiveSources(
        visiting: MutableSet<MappingsSpecImpl> = mutableSetOf()
    ): List<Source> {
        val own = sources.ifEmpty {
            val inherited = inheritedSources ?: return defaultSources.toList()
            if (!visiting.add(this)) {
                throw GradleException("cyclic Unimined mapping inheritance detected")
            }
            try {
                inherited(visiting)
            } finally {
                visiting.remove(this)
            }
        }
        return defaultSources + deferredDefaultSources.flatMap { it(this) } + own
    }

    /** A declared mapping source before materialization. */
    sealed class Source : MappingSource {
        override var formatHint: String? = null
        override val nsMap: MutableMap<String, String> = mutableMapOf()
        override val namedNamespaces: MutableSet<String> = mutableSetOf()

        /** Force the document's format — a name the consumer's format
         *  registry understands (e.g. a UML `FormatProvider` name). */
        fun formatHint(hint: String) {
            formatHint = hint
        }

        /** Relabel document namespace [from] to chain namespace [to] —
         *  for documents whose labels don't match the environment chain. */
        fun mapNamespace(from: String, to: String) {
            nsMap[from] = to
        }

        /** Relabel several document namespaces at once. */
        fun mapNamespace(vararg pairs: Pair<String, String>) {
            pairs.forEach { (from, to) -> mapNamespace(from, to) }
        }

        /**
         * Tag this source's jar-side namespace [srcNs] as belonging to
         * environment [env] — its rows land in the `srcNs:env` column
         * (`envView(E) = ns ∪ ns:env`). For env-free formats (Ornithe
         * gen-1 `-client`/`-server` artifacts, split SRG files) this is
         * the file-level default; formats carrying per-row env (MCP v6)
         * tag rows themselves. [srcNs] is the source's jar-native
         * (obf) column — `official` by default.
         */
        fun env(env: String, srcNs: String = "official") {
            mapNamespace(srcNs, "$srcNs:$env")
        }

        /** Flag emitted chain namespaces as *named* (developer-facing)
         *  — feeds the environment's default dev namespace. Builtin
         *  channels flag their own; declare this for custom sources
         *  whose named column isn't labeled `"named"`. */
        fun named(vararg namespaces: String) {
            namedNamespaces += namespaces
        }

        /** A local mapping file collection resolved lazily. */
        class File(fileCollection: ConfigurableFileCollection) : Source(), FileMappingSource {
            override val files: () -> List<java.nio.file.Path> =
                { fileCollection.files.map { it.toPath() } }
        }

        /** A Maven mapping coordinate resolved lazily. */
        class Maven(val notationProvider: Provider<String>) : Source(), MavenMappingSource {
            override val notation: String get() = notationProvider.get()
        }

        /** A provider-named built-in mapping channel — [nameProvider]
         *  is only realized at materialization, so transform wiring can
         *  key the channel off a lazily-probed profile. */
        class Builtin(
            val nameProvider: Provider<String>,
            providerParams: Map<String, Provider<String>> = emptyMap()
        ) : Source(), BuiltinMappingSource {
            override val params: Map<String, () -> String> =
                providerParams.mapValues { v -> { v.value.get() } }
            override val channel get() = nameProvider.get()
        }
    }

    /**
     * mcphackers artifacts are keyed by the shared obf jar, not the
     * release id — b1.7.3's mappings live at `versionsV2/b1.7.zip`.
     * Resolve the version through the BetterJSONs manifest, falling
     * back to the literal [version] so zip keys like `b1.7` pass
     * through unchanged.
     */
    private fun retroMCPArtifactKey(version: String): String = try {
        retroMCPManifest.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == version
        }?.jsonObject?.get("resources")?.jsonPrimitive?.content
            ?.substringAfterLast('/')?.removeSuffix(".zip")
            ?: version
    } catch (e: Exception) {
        version
    }

    companion object {
        private val retroMCPManifest by lazy {
            kotlinx.serialization.json.Json.parseToJsonElement(
                java.net.URI("https://mcphackers.org/versionsV3/versions.json")
                    .toURL().openConnection().apply {
                        connectTimeout = 15_000; readTimeout = 15_000
                    }.getInputStream().bufferedReader().readText()
            ).jsonArray
        }

    }
}
