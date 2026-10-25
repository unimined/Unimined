package xyz.wagyourtail.unimined.mapping.sources

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.MavenMappingSource
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource

/** Materializes a Maven-coordinate source through the host's resolver. */
class MavenMappingSourceProvider : MappingSourceProvider {

    override val name = "maven"

    override fun supports(spec: MappingSource): Boolean = spec is MavenMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as MavenMappingSource
        return RuntimeMaterializedMappingSource(
            source,
            listOf(ctx.resolveMaven(source.notation)),
            source.formatHint
        )
    }

}
