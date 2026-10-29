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

    /**
     * Optional namespace relabeling applied while reading this source —
     * maps the document's own namespace names onto chain-namespace names
     * (`mapNamespace("mojmap", "named")`). Empty = read labels verbatim.
     */
    val nsMap: Map<String, String>
        get() = emptyMap()

    /**
     * Emitted tree namespaces (post-[nsMap] chain names) carrying
     * *named* — developer-facing — identifiers. The resolver unions
     * these across sources in declaration order; an environment's dev
     * namespace defaults to the first named namespace (1.x
     * `outputs(ns, named)` role flag). A document column literally
     * named `"named"` is flagged automatically — declare here for
     * differently-labeled named columns (`"mojmap"`, `"mcp"`).
     */
    val namedNamespaces: Set<String>
        get() = emptySet()

}
