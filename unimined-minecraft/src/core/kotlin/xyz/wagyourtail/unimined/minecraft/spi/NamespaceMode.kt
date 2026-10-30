package xyz.wagyourtail.unimined.minecraft.spi

/**
 * Whether a provider's source artifact names require a namespace-changing
 * remap before they can serve a dev classpath.
 */
enum class NamespaceMode {

    /** Source jar carries an obfuscated namespace; a real remap step runs. */
    REMAPPABLE,

    /** Source jar is already in the consumable namespace; remap is a no-op. */
    IDENTITY

}
