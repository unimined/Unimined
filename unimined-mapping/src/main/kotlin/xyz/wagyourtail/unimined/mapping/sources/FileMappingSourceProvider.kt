package xyz.wagyourtail.unimined.mapping.sources

import java.nio.file.Files
import xyz.wagyourtail.unimined.api.mapping.FileMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.mapping.internal.MappingArchive
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource

/** Materializes a declared local-file source — resolves the file
 *  collection, unwrapping archive artifacts via [MappingArchive]. */
class FileMappingSourceProvider : MappingSourceProvider {

    override val name = "file"

    override fun supports(spec: MappingSource): Boolean = spec is FileMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as FileMappingSource
        val cacheDir = ctx.cacheDir
            ?: Files.createTempDirectory("unimined-mappings")
        // deterministic order for fingerprinting and composition
        val files = source.files.files.map { it.toPath() }.sorted()
            .flatMap { file -> MappingArchive.extract(file, cacheDir) ?: listOf(file) }
        return RuntimeMaterializedMappingSource(source, files, source.formatHint)
    }

}
