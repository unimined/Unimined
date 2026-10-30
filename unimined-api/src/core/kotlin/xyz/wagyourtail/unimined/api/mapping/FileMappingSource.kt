package xyz.wagyourtail.unimined.api.mapping

import java.nio.file.Path

/** A declared source backed by local mapping file(s). */
interface FileMappingSource : MappingSource {

    /** The declared files — resolved lazily by the declaring host. */
    val files: () -> List<Path>

}
