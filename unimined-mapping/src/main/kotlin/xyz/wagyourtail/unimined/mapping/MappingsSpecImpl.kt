package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.BuiltinMappingSource
import xyz.wagyourtail.unimined.api.mapping.FileMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MavenMappingSource
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
    fun builtin(name: String, action: Source.() -> Unit = {}) {
        sources += Source.Builtin(name).apply(action)
    }

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

    /** Declare the Searge built-in mappings channel. */
    fun searge() = builtin("searge")

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
        val own = if (sources.isNotEmpty()) {
            sources
        } else {
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

        /** A local mapping file collection resolved lazily. */
        class File(override val files: ConfigurableFileCollection) : Source(), FileMappingSource

        /** A Maven mapping coordinate resolved lazily. */
        class Maven(val notationProvider: Provider<String>) : Source(), MavenMappingSource {
            override val notation: String get() = notationProvider.get()
        }

        /** A provider-named built-in mapping channel. */
        class Builtin(val name: String) : Source(), BuiltinMappingSource {
            override val channel get() = name
        }
    }

}
