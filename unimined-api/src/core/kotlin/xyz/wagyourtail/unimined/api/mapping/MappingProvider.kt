package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.api.spi.Supports

/**
 * Reads one materialized source into [MappingData] with zero or more typed
 * views (UML, mapping-io, ...). Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingProvider`.
 */
interface MappingProvider : Supports<MaterializedMappingSource> {

    /** Read and resolve [source] into typed mapping views. */
    fun resolve(source: MaterializedMappingSource, ctx: MappingContext): MappingData

}
