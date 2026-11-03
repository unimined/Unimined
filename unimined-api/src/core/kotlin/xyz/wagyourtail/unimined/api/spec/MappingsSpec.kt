package xyz.wagyourtail.unimined.api.spec

/**
 * The mappings declaration for an environment: the ordered namespace chain
 * plus the mapping sources that populate it.
 *
 * Mapping *source resolution* (this spec → a resolved mapping tree) is
 * performed by `unimined-mapping` — engines receive the resolved tree (or
 * its interchange form), never reimplement resolution.
 */
interface MappingsSpec {

    /**
     * Ordered namespace names forming the mapping chain,
     * e.g. `["official", "intermediary", "named"]`.
     */
    val namespaces: List<String>

}
