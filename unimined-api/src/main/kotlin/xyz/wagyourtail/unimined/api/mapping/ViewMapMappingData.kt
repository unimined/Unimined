package xyz.wagyourtail.unimined.api.mapping

import kotlin.reflect.KClass

/**
 * [MappingData] backed by an explicit view map — the shape composers and
 * serializers produce when merging or rehydrating arbitrary view kinds.
 */
class ViewMapMappingData(
    private val views: Map<KClass<*>, Any>,
    override val properties: Map<String, String> = emptyMap()
) : MappingData {

    override val viewTypes: Set<KClass<*>>
        get() = views.keys

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> view(type: Class<T>): T? =
        views[type.kotlin] as T?

}
