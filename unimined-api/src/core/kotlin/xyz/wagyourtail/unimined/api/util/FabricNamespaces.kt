package xyz.wagyourtail.unimined.api.util

/**
 * Canonical values for the `Fabric-Mapping-Namespace` manifest
 * attribute — fabric-ecosystem tooling recognizes only `official`,
 * `intermediary` and `named` (loom writes `named` for every
 * named-family target). Unimined's internal namespace names
 * (`mojmap`, `yarn`, `calamus`, `feather`, parchment-over-*) must
 * not leak into this attribute.
 */
object FabricNamespaces {

    private val OFFICIAL_FAMILY = setOf(
        "official", "clientOfficial", "serverOfficial"
    )

    /**
     * The canonical fabric name for [namespace]: anything in
     * [namedNamespaces] (or literally `named`) is a named-family
     * runtime namespace → `named`; the official family → `official`;
     * everything else is an intermediary-position namespace
     * (`intermediary`, `calamus`, `hashed`, …) → `intermediary`.
     *
     * [namedNamespaces] is the resolved mapping data's named flag
     * ([xyz.wagyourtail.unimined.api.mapping.namedNamespaces]) — the
     * same role information that resolves the dev namespace.
     */
    fun canonical(namespace: String, namedNamespaces: Set<String>): String = when {
        namespace == "named" || namespace in namedNamespaces -> "named"
        namespace in OFFICIAL_FAMILY -> "official"
        else -> "intermediary"
    }

}
