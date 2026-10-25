package xyz.wagyourtail.unimined.minecraft.spi

import kotlinx.serialization.json.JsonObject

/**
 * Launch metadata flowing provider → transform → runs (see Decisions).
 *
 * Providers emit raw version JSONs (forge `version.json`, fg2-userdev
 * `dev.json`, fabric/neoforge loader json, mavenizer `runs.json`-derived
 * profiles); `mc-launcher`'s shared `resolveVersionChain` merges
 * `inheritsFrom` chains — transforms and providers never hand-pick
 * fields out of the JSON.
 */
interface LaunchMetadata {

    /** The environment's version JSON — may declare `inheritsFrom`. */
    val root: JsonObject

    /**
     * Fetch the parent version JSON for [id] during `inheritsFrom`
     * resolution — from the provider's metadata source (version manifest,
     * embedded loader JSON, ...).
     */
    fun parent(id: String): JsonObject

}
