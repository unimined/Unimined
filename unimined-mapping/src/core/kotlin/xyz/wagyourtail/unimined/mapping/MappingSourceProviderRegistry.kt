package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.mapping.sources.BuiltinMappingSourceProvider
import xyz.wagyourtail.unimined.mapping.sources.FileMappingSourceProvider
import xyz.wagyourtail.unimined.mapping.sources.MavenMappingSourceProvider
import java.util.*

/**
 * Registry of [MappingSourceProvider] materializers — `file`, `maven`, and
 * `builtin` channels are host builtins; custom declaration types arrive via
 * `META-INF/services` or [register].
 */
class MappingSourceProviderRegistry {

    private val selector: SpiSelector<MappingSourceProvider, MappingSource> =
        spiSelector("mapping source provider")

    init {
        val found = ServiceLoader.load(
            MappingSourceProvider::class.java,
            MappingSourceProvider::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingSourceProvider::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(selector::register)

        selector.register(FileMappingSourceProvider())
        selector.register(MavenMappingSourceProvider())
        selector.register(BuiltinMappingSourceProvider())
    }

    /** Register a source materializer. */
    fun register(provider: MappingSourceProvider) {
        selector.register(provider)
    }

    /** Resolve the materializer for [source], optionally named by [override]. */
    fun select(
        source: MappingSource,
        override: String? = null
    ): MappingSourceProvider = selector.select(source, override)

}
