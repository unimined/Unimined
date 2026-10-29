package xyz.wagyourtail.unimined.mapping.sources

import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.internal.MappingArchive
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource
import java.nio.file.Files
import java.nio.file.Path

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
        val files = mutableListOf<Path>()
        val attachments = linkedMapOf<String, Path>()
        source.files.files.map { it.toPath() }.sorted().forEach { file ->
            val contents = MappingArchive.extractAll(file, cacheDir)
            if (contents == null) files.add(file) else {
                files.addAll(contents.documents)
                attachments.putAll(contents.attachments)
            }
        }
        return RuntimeMaterializedMappingSource(
            source, files, source.formatHint, attachments = attachments
        )
    }

}
