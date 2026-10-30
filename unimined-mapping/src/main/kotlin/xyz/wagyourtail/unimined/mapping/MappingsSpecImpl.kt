package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.spec.MappingsSpec

/**
 * The `mappings { }` block state: the declared namespace chain plus the
 * ordered list of mapping sources feeding it.
 *
 * @param mcVersion the owning environment's Minecraft version — used by
 *  channel sugar such as [yarn] to default the artifact version; may be
 *  null on detached spec instances
 */
class MappingsSpecImpl(
    objects: ObjectFactory,
    private val mcVersion: Provider<String>? = null
) : MappingsSpec {

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

    private var inheritedSources: ((MutableSet<MappingsSpecImpl>) -> List<Source>)? = null

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
        "io.github.mcphackers:mcp:$version@zip"
    ) { named("retroMCP") }

    /**
     * Declare Ornithe Feather mappings for the environment's Minecraft
     * version — `feather(4)` ⇢ `net.ornithemc:feather:<mc>+build.4:v2`
     * on the Ornithe maven; rides the `intermediary` hop.
     */
    fun feather(build: Int) = maven(
        requireMcVersion("feather").map { "net.ornithemc:feather:$it+build.$build:v2" }
    )

    /**
     * Declare Legacy Fabric Yarn for the environment's Minecraft
     * version — `legacyYarn(510)` ⇢
     * `net.legacyfabric:yarn:<mc>+build.510:v2` on the Legacy Fabric
     * maven; rides the `intermediary` hop.
     */
    fun legacyYarn(build: Int) = maven(
        requireMcVersion("legacyYarn").map { "net.legacyfabric:yarn:$it+build.$build:v2" }
    )

    /**
     * Declare Babric Barn mappings for the environment's Minecraft
     * version — `barn(17)` ⇢ `babric:barn:<mc>+build.17:v2` on the
     * Glass Launcher babric maven; rides the `babricIntermediary` hop.
     */
    fun barn(build: Int) = maven(
        requireMcVersion("barn").map { "babric:barn:$it+build.$build:v2" }
    )

    /**
     * Declare Quilt mappings for the environment's Minecraft version —
     * `quilt(12)` ⇢ `org.quiltmc:quilt-mappings:<mc>+build.12:intermediary-v2`
     * on the Quilt maven; rides the `intermediary` hop.
     */
    fun quilt(build: Int) = maven(
        requireMcVersion("quilt").map { "org.quiltmc:quilt-mappings:$it+build.$build:intermediary-v2" }
    )

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
        return defaultSources + own
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

        /** Flag emitted chain namespaces as *named* (developer-facing)
         *  — feeds the environment's default dev namespace. Builtin
         *  channels flag their own; declare this for custom sources
         *  whose named column isn't labeled `"named"`. */
        fun named(vararg namespaces: String) {
            namedNamespaces += namespaces
        }

        /** A local mapping file collection resolved lazily. */
        class File(override val files: ConfigurableFileCollection) : Source(), FileMappingSource

        /** A Maven mapping coordinate resolved lazily. */
        class Maven(val notationProvider: Provider<String>) : Source(), MavenMappingSource {
            override val notation: String get() = notationProvider.get()
        }

        /** A provider-named built-in mapping channel — [nameProvider]
         *  is only realized at materialization, so transform wiring can
         *  key the channel off a lazily-probed profile. */
        class Builtin(
            val nameProvider: Provider<String>,
            override val params: Map<String, Provider<String>> = emptyMap()
        ) : Source(), BuiltinMappingSource {
            override val channel get() = nameProvider.get()
        }
    }

}
