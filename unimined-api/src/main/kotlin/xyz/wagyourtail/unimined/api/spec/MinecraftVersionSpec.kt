package xyz.wagyourtail.unimined.api.spec

/**
 * Identifies a Minecraft version a provider is asked to resolve.
 *
 * Kept minimal on purpose: providers that need richer version semantics
 * (snapshots, loader-supplied ranges) receive the raw string and resolve
 * it against their own metadata source.
 */
interface MinecraftVersionSpec {

    /** The version id, e.g. `"1.20.1"`, `"23w13a"`, `"b1.7.3"`. */
    val version: String

}
