package xyz.wagyourtail.unimined.mapping

import java.util.ServiceLoader
import xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest
import xyz.wagyourtail.unimined.api.mapping.MappingComposer
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector

/**
 * Registry of [MappingComposer]s — the host's [DefaultMappingComposer] is
 * the fallback; richer composers arrive via `META-INF/services` or
 * [register]. A fresh registry at task execution sees the same composers
 * as the configuration-time registry.
 */
class MappingComposerRegistry(
    viewAdapters: MappingViewAdapterRegistry = MappingViewAdapterRegistry()
) {

    private val selector: SpiSelector<MappingComposer, MappingComposeRequest> =
        spiSelector("mapping composer")

    init {
        val found = ServiceLoader.load(
            MappingComposer::class.java,
            MappingComposer::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingComposer::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(selector::register)

        selector.register(DefaultMappingComposer(viewAdapters))
    }

    /** Register a composer implementation. */
    fun register(composer: MappingComposer) {
        selector.register(composer)
    }

    /** Resolve the composer for [request], optionally named by [override]. */
    fun select(
        request: MappingComposeRequest,
        override: String? = null
    ): MappingComposer = selector.select(request, override)

}
