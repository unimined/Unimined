package xyz.wagyourtail.unimined.api.mapping

import java.nio.file.Path

/**
 * A declared [MappingSource] after materialization to local files.
 * A source may produce more than one inner file — e.g. expanded archive
 * contents or multi-part declarations.
 */
interface MaterializedMappingSource {

    /** The declaration this materialization came from. */
    val declaration: MappingSource

    /** The local mapping files, in declaration order. */
    val files: List<Path>

    /** Format hint honored before autodetect, propagated from the declaration. */
    val formatHint: String?

    /** Namespace relabeling propagated from the declaration — applied
     *  while reading the materialized files into the composed tree. */
    val nsMap: Map<String, String>
        get() = emptyMap()

}
