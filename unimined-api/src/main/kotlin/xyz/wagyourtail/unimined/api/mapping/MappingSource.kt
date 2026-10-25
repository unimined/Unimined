package xyz.wagyourtail.unimined.api.mapping

/**
 * A declared mapping source before materialization — local files, maven
 * coordinates, provider/builtin channels, or custom declaration types
 * contributed by third parties.
 *
 * Declarations are produced by the `mappings { }` DSL and materialized by
 * a [MappingSourceProvider] into a [MaterializedMappingSource].
 */
interface MappingSource {

    /**
     * Optional format hint consulted before autodetect — a format name the
     * consumer's registry understands (e.g. a UML `FormatProvider` name).
     */
    val formatHint: String?
        get() = null

}
