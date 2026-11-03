package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.api.spi.Supports

/**
 * Turns a [MappingSource] declaration into local mapping files without
 * parsing them. Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider`.
 */
interface MappingSourceProvider : Supports<MappingSource> {

    /** Materialize [source] into local files for a [MappingProvider] to read. */
    fun materialize(source: MappingSource, ctx: MappingContext): MaterializedMappingSource

}
