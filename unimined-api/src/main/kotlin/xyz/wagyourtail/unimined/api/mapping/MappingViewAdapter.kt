package xyz.wagyourtail.unimined.api.mapping

import kotlin.reflect.KClass

/**
 * Derives an equivalent [MappingDataView] from the views a [MappingData]
 * already exposes — e.g. reconstructing a minimal [UmlMappingTreeView]
 * from portable [MappingTree] rows so a portable-only source can still
 * feed a UML-based remap step.
 *
 * Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingViewAdapter`
 * and may also be registered directly on the mappings extension.
 *
 * Adapters fill *gaps*: they are consulted only for view kinds the data
 * does not already expose, so a richer native view always wins over an
 * adapted one.
 */
interface MappingViewAdapter {

    /** The view kind this adapter produces. */
    val produces: KClass<out MappingDataView>

    /**
     * The view kinds [adapt] requires — the adapter is attempted only when
     * every kind is present on the input [MappingData].
     */
    val consumes: Set<KClass<out MappingDataView>>

    /**
     * Derive [produces] from [data]'s exposed views.
     *
     * @return the derived view, or `null` when the inputs are present but
     *  unusable (e.g. empty namespace list)
     */
    fun adapt(data: MappingData): MappingDataView?

}
