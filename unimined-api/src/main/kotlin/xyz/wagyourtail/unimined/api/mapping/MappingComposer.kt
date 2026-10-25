package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.api.spi.Supports

/**
 * Ordered per-source [MappingData] parts the composer is asked to merge
 * into one environment-level [MappingData].
 */
class MappingComposeRequest(
    /** Resolved parts in declared source order. */
    val parts: List<MappingData>
) {

    override fun toString() = "MappingComposeRequest(${parts.size} sources)"

}

/**
 * Merges ordered per-source data into one environment [MappingData],
 * preserving each supported view rather than flattening everything into
 * rename rows. Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingComposer`.
 */
interface MappingComposer : Supports<MappingComposeRequest> {

    /** Compose [request]'s ordered parts into one environment MappingData. */
    fun compose(request: MappingComposeRequest, ctx: MappingContext): MappingData

}
