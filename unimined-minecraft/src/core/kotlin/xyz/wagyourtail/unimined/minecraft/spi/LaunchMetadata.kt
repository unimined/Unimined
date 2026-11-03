package xyz.wagyourtail.unimined.minecraft.spi

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

/**
 * Launch metadata flowing provider → transform → runs (see Decisions).
 *
 * Providers emit raw version JSONs (forge `version.json`, fg2-userdev
 * `dev.json`, fabric/neoforge loader json, mavenizer `runs.json`-derived
 * profiles); `mc-launcher`'s shared `resolveVersionChain` merges
 * `inheritsFrom` chains — transforms and providers never hand-pick
 * fields out of the JSON.
 *
 * Implementations carry only *reconstruction data* (ids, cache roots,
 * cache policy flags) and rebuild their backend lazily inside
 * [versionJson]. That keeps instances [Serializable] so run tasks can
 * hold them through configuration-cache serialization without capturing
 * live network or Gradle services.
 */
interface LaunchMetadata : Serializable {

    /** Id of the root version document — the entry point of the chain. */
    val rootId: String

    /**
     * Load a version document — the root when [id] is [rootId], or an
     * `inheritsFrom` parent — from the provider's metadata source
     * (version manifest, embedded loader JSON, mavenizer store, ...).
     * May hit the cache or network; only invoked at execution time.
     */
    fun versionJson(id: String): JsonObject

    /** The environment's version JSON — may declare `inheritsFrom`. */
    val root: JsonObject
        get() = versionJson(rootId)

}
