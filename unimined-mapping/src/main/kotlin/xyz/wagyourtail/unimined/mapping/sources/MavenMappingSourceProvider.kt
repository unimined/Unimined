package xyz.wagyourtail.unimined.mapping.sources

import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.internal.MappingArchive
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource
import java.nio.file.Files

/**
 * Materializes a Maven-coordinate source through the host's resolver —
 * and unwraps jar artifacts (the `yarn`/`intermediary`
 * `mappings/mappings.tiny` convention) via [MappingArchive].
 */
class MavenMappingSourceProvider : MappingSourceProvider {

    override val name = "maven"

    override fun supports(spec: MappingSource): Boolean = spec is MavenMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as MavenMappingSource
        val resolved = ctx.resolveMaven(source.notation)
        val cacheDir = ctx.cacheDir
            ?: Files.createTempDirectory("unimined-mappings")
        val contents = MappingArchive.extractAll(resolved, cacheDir)
        return RuntimeMaterializedMappingSource(
            source,
            contents?.documents ?: listOf(resolved),
            source.formatHint,
            attachments = contents?.attachments ?: emptyMap()
        )
    }

}
