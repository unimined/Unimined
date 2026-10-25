package xyz.wagyourtail.unimined.api.spec

/**
 * A modloader (or other environment layer) applied on top of a Minecraft
 * version — fabric, forge, jarmod, liteloader, ... — plus the loader
 * version requested.
 */
interface LoaderSpec {

    /** Loader family name, e.g. `"fabric"`, `"forge"`, `"neoforge"`. */
    val name: String

    /** Loader version, e.g. `"0.16.0"`, `"47.2.0"`. May be a range
     *  expression where the loader family supports one. */
    val version: String

}
