package xyz.wagyourtail.unimined.api.mapping

import kotlin.reflect.KClass

/**
 * Persists one related family of [MappingDataView] kinds into a
 * [SerializedMappingData] bundle payload — e.g. the UML tree serializer,
 * whose single payload rehydrates both [UmlMappingTreeView] and the
 * portable [MappingTree] view.
 *
 * Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingViewSerializer`
 * and must be on the classpath wherever a bundle is written or read.
 */
interface MappingViewSerializer {

    /**
     * Unique id recorded in the bundle manifest — payload files are named
     * after it, so it must be stable and filename-safe.
     */
    val id: String

    /**
     * View kinds this serializer requires on the source [MappingData] —
     * [write] runs only when every kind is present. The rehydrated
     * [MappingData] may expose more kinds than listed here (a UML payload
     * can serve the portable view too).
     */
    val views: Set<KClass<out MappingDataView>>

    /**
     * Serialize this impl's views from [data].
     *
     * @return the payload bytes, or `null` when the data's views can't be
     *  represented by this serializer
     */
    fun write(data: MappingData): ByteArray?

    /**
     * Rehydrate this impl's views from [payload] into a [MappingData]
     * fragment, later merged into the bundle's view map.
     */
    fun read(payload: ByteArray): MappingData

}
