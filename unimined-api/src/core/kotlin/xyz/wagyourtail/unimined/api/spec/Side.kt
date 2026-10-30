package xyz.wagyourtail.unimined.api.spec

/**
 * Which Minecraft side an environment targets.
 */
enum class Side {
    CLIENT,
    SERVER,

    /** Merged client+server jar (the default for most development). */
    MERGED
}
