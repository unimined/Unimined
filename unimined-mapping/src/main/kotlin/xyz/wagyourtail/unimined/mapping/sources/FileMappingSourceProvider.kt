package xyz.wagyourtail.unimined.mapping.sources

import xyz.wagyourtail.unimined.api.mapping.FileMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource

/** Materializes a declared local-file source — resolves the file collection. */
class FileMappingSourceProvider : MappingSourceProvider {

    override val name = "file"

    override fun supports(spec: MappingSource): Boolean = spec is FileMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as FileMappingSource
        return RuntimeMaterializedMappingSource(
            source,
            // deterministic order for fingerprinting and composition
            source.files.files.map { it.toPath() }.sorted(),
            source.formatHint
        )
    }

}
