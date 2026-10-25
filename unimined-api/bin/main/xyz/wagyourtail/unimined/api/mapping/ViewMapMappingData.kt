package xyz.wagyourtail.unimined.api.mapping

import kotlin.reflect.KClass

/**
 * [MappingData] backed by an explicit view map — the shape composers and
 * serializers produce when merging or rehydrating arbitrary view kinds.
 */
class ViewMapMappingData(
    private val views: Map<KClass<out MappingDataView>, MappingDataView>
) : MappingData {

    override val viewTypes: Set<KClass<out MappingDataView>>
        get() = views.keys

    @Suppress("UNCHECKED_CAST")
    override fun <T : MappingDataView> view(type: Class<T>): T? =
        views[type.kotlin] as T?

}
