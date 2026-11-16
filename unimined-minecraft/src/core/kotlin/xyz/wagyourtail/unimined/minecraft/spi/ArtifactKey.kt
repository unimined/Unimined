package xyz.wagyourtail.unimined.minecraft.spi

/**
 * Keys into [ProductArtifacts.artifacts].
 */
enum class ArtifactKey {
    CLIENT,
    SERVER,
    MERGED,
    SOURCES,

    /**
     * Intersection of the [CLIENT] and [SERVER] distributions — the
     * "common" jar used when environment source sets are split. Providers
     * without genuinely split distributions alias this to [MERGED].
     */
    COMMON
}
