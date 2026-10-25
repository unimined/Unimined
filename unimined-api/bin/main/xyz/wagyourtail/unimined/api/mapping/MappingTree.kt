package xyz.wagyourtail.unimined.api.mapping

/**
 * The composed namespace→namespace mapping tree — the canonical view
 * consumed by `remap` step implementations.
 *
 * This is the interchange contract: the mappings core resolves the
 * `mappings { }` spec into an implementation of this interface, so
 * engines never reimplement source resolution. Backed by
 * `UniminedMappingLibrary` internally.
 */
interface MappingTree : MappingDataView {

    /** Ordered namespace names, e.g. `["official", "intermediary", "named"]`. */
    val namespaces: List<String>

    /**
     * Visit all mappings from [sourceNamespace] to [targetNamespace],
     * emitting one callback per mapped member in declaration order.
     * [sourceNamespace] and [targetNamespace] must both be in
     * [namespaces]; identical namespaces emit the identity mapping.
     */
    fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor)

}
