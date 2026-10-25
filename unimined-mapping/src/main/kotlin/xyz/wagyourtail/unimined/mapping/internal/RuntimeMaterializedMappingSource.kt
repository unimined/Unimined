package xyz.wagyourtail.unimined.mapping.internal

import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import java.nio.file.Path

internal class RuntimeMaterializedMappingSource(
    override val declaration: MappingSource,
    override val files: List<Path>,
    override val formatHint: String?
) : MaterializedMappingSource
