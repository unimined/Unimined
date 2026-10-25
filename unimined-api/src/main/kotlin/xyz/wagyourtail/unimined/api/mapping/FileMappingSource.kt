package xyz.wagyourtail.unimined.api.mapping

import org.gradle.api.file.FileCollection

/** A declared source backed by local mapping file(s). */
interface FileMappingSource : MappingSource {

    /** The declared files — resolved lazily by the declaring host. */
    val files: FileCollection

}
