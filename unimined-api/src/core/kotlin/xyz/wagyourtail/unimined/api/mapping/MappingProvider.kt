package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.api.spi.Supports
import xyz.wagyourtail.unimined.mapping.MappingTree

/**
 * Reads one materialized source into [MappingData] with zero or more typed
 * views (UML, mapping-io, ...). Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingProvider`.
 */
interface MappingProvider : Supports<MaterializedMappingSource> {

    /** Read and resolve [source] into typed mapping views. */
    fun resolve(source: MaterializedMappingSource, ctx: MappingContext): MappingData

    /**
     * [resolve] with an overlay [context] — the union of the trees
     * resolved from earlier-declared sources. Context-dependent readers
     * (the MCP csv family: `fields.csv`/`methods.csv`/`params.csv` ship
     * member names keyed by `searge` with no owning class) rewrite rows
     * streamed from the context tree, so archives carrying only csvs
     * (`mcp_snapshot`/`mcp_stable`) still see the base document from a
     * sibling source. The default ignores [context] — providers without
     * context-dependent formats behave identically either way.
     */
    fun resolve(
        source: MaterializedMappingSource,
        ctx: MappingContext,
        context: MappingTree?
    ): MappingData = resolve(source, ctx)

}
