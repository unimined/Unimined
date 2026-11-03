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

    /** Named-namespace flags propagated from the declaration — the
     *  emitted tree namespaces carrying developer-facing names. */
    val namedNamespaces: Set<String>
        get() = declaration.namedNamespaces

    /**
     * Companion files extracted alongside the mapping documents — the
     * yarn `v2` convention of shipping `extras/record_signatures.json`
     * (and future extras) inside the published jar. Keyed by archive
     * entry path; empty for non-archive sources.
     */
    val attachments: Map<String, Path>
        get() = emptyMap()

}
