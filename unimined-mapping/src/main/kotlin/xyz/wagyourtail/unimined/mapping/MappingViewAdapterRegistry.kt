package xyz.wagyourtail.unimined.mapping

import java.util.ServiceLoader
import kotlin.reflect.KClass
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingViewAdapter
import xyz.wagyourtail.unimined.mapping.adapters.PortableToUmlTreeAdapter

/**
 * Registry of [MappingViewAdapter]s — [PortableToUmlTreeAdapter] is the
 * bundled default; richer adapters arrive via `META-INF/services` or
 * [register]. A fresh registry at task execution sees the same adapters
 * as the configuration-time registry.
 */
class MappingViewAdapterRegistry {

    private val adapters = mutableListOf<MappingViewAdapter>()

    init {
        val found = ServiceLoader.load(
            MappingViewAdapter::class.java,
            MappingViewAdapter::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingViewAdapter::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it::class }.forEach(::register)

        register(PortableToUmlTreeAdapter())
    }

    /** Register an adapter implementation. */
    fun register(adapter: MappingViewAdapter) {
        if (adapters.none { it::class == adapter::class }) adapters += adapter
    }

    /**
     * @return `true` when some registered adapter produces [type] and every
     *  view kind it consumes is present on [data]
     */
    fun canProduce(type: KClass<out MappingDataView>, data: MappingData): Boolean =
        adapters.any {
            it.produces == type && data.viewTypes.containsAll(it.consumes)
        }

    /**
     * Derive a [type] view from [data]'s exposed views — the first adapter
     * (registration order) whose inputs are all present and that returns a
     * non-null view wins.
     *
     * @return the derived view, or `null` when no adapter could produce it
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : MappingDataView> adapt(data: MappingData, type: Class<T>): T? {
        // adapters fill gaps only — a natively exposed view always wins
        if (data.viewTypes.contains(type.kotlin)) return null
        for (adapter in adapters) {
            if (adapter.produces != type.kotlin) continue
            if (!data.viewTypes.containsAll(adapter.consumes)) continue
            val view = adapter.adapt(data)
            if (view != null) return view as T
        }
        return null
    }

}
