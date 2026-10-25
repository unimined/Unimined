package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.api.mapping.BuiltinMappingSource
import xyz.wagyourtail.unimined.api.mapping.FileMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MavenMappingSource
import xyz.wagyourtail.unimined.api.spec.MappingsSpec

/**
 * The `mappings { }` block state: the declared namespace chain plus the
 * ordered list of mapping sources feeding it.
 */
class MappingsSpecImpl(
    objects: ObjectFactory
) : MappingsSpec {

    private val fileCollectionFactory = objects

    /** Mapping sources declared directly on this environment, in chain order. */
    val sources = mutableListOf<Source>()

    private var inheritedSources: ((MutableSet<MappingsSpecImpl>) -> List<Source>)? = null

    /** Namespace chain — populated by the mapping host at resolve time. */
    override var namespaces: List<String> = emptyList()
        internal set

    /** Declare a local mapping file source. */
    fun file(path: Any) {
        sources += Source.File(fileCollectionFactory.fileCollection().from(path))
    }

    /** Declare a maven-coordinate mapping source — resolved lazily. */
    fun maven(notation: String) {
        sources += Source.Maven(notation)
    }

    /** Declare a provider builtin channel such as `official()`. */
    fun builtin(name: String) {
        sources += Source.Builtin(name)
    }

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

    /** Mapping declarations after applying environment inheritance. */
    @JvmOverloads
    fun effectiveSources(
        visiting: MutableSet<MappingsSpecImpl> = mutableSetOf()
    ): List<Source> {
        if (sources.isNotEmpty()) return sources
        val inherited = inheritedSources ?: return emptyList()
        if (!visiting.add(this)) {
            throw GradleException("cyclic Unimined mapping inheritance detected")
        }
        try {
            return inherited(visiting)
        } finally {
            visiting.remove(this)
        }
    }

    /** A declared mapping source before materialization. */
    sealed class Source : MappingSource {
        override var formatHint: String? = null

        /** A local mapping file collection resolved lazily. */
        class File(override val files: ConfigurableFileCollection) : Source(), FileMappingSource

        /** A Maven mapping coordinate resolved lazily. */
        class Maven(override val notation: String) : Source(), MavenMappingSource

        /** A provider-named built-in mapping channel. */
        class Builtin(val name: String) : Source(), BuiltinMappingSource {
            override val channel get() = name
        }
    }

}
