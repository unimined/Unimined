package xyz.wagyourtail.unimined.api.spec

/**
 * Which Minecraft side an environment targets.
 */
enum class Side {
    CLIENT,
    SERVER,

    /** Merged client+server jar (the default for most development). */
    MERGED,

    /**
     * Classes present on both distributions (intersection of the client
     * and server jars). Used by `splitEnvironmentSourceSets` for the
     * `main` source set; providers without genuinely split distributions
     * alias this to [MERGED].
     */
    COMMON
}
