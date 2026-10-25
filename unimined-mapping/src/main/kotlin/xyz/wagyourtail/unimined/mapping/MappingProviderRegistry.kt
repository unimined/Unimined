package xyz.wagyourtail.unimined.mapping

import java.util.ServiceLoader
import xyz.wagyourtail.unimined.api.mapping.MappingProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector

/**
 * Registry of mapping providers discovered from
 * `META-INF/services`. One provider is selected per materialized source;
 * a fresh registry at task execution sees the same providers as the
 * configuration-time registry.
 */
class MappingProviderRegistry {

    private val selector: SpiSelector<MappingProvider, MaterializedMappingSource> =
        spiSelector("mapping provider")

    init {
        val found = ServiceLoader.load(
            MappingProvider::class.java,
            MappingProvider::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingProvider::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(selector::register)
    }

    /** Register a mapping provider implementation. */
    fun register(provider: MappingProvider) {
        selector.register(provider)
    }

    /** Resolve the provider for [source], optionally named by [override]. */
    fun select(
        source: MaterializedMappingSource,
        override: String? = null
    ): MappingProvider = selector.select(source, override)

}
