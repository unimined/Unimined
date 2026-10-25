package xyz.wagyourtail.unimined.api.mapping

/**
 * Resolved mapping data for an environment, exposed as typed views.
 */
interface MappingData {

    /** The view kinds this data exposes — composers/serializers enumerate it. */
    val viewTypes: Set<kotlin.reflect.KClass<out MappingDataView>>

    /**
     * @return the requested view, or `null` if the resolved mapping data
     *  does not provide that view kind
     */
    fun <T : MappingDataView> view(type: Class<T>): T?

}

/**
 * Kotlin convenience for [MappingData.view].
 */
inline fun <reified T : MappingDataView> MappingData.view(): T? = view(T::class.java)
