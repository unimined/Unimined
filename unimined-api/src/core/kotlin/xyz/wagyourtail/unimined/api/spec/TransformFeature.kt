package xyz.wagyourtail.unimined.api.spec

/**
 * Cross-cutting feature flags a transform or decorator can declare
 * support for / require.
 */
enum class TransformFeature {

    /** Fabric-style access wideners. */
    ACCESS_WIDENER,

    /** Forge-style access transformers. */
    ACCESS_TRANSFORMER,

    /** Interface injection onto existing classes. */
    INTERFACE_INJECTION,

    /** Mixin refmap generation/remapping. */
    MIXIN_REFMAP

}
