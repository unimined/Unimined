package xyz.wagyourtail.unimined.mapping.internal

import kotlin.getValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RetroMCP coordinate support — mcphackers artifacts are keyed by the
 * shared obf jar, not the release id: b1.7.3's mappings live at
 * `versionsV2/b1.7.zip`. [retroMCPArtifactKey] resolves a release
 * version through the BetterJSONs manifest to that key, falling back
 * to the literal version so zip keys like `b1.7` pass through
 * unchanged.
 */
internal object RetroMCPManifest {

    private val manifest by lazy {
        kotlinx.serialization.json.Json.parseToJsonElement(
            java.net.URI("https://mcphackers.org/versionsV3/versions.json")
                .toURL().openConnection().apply {
                    connectTimeout = 15_000; readTimeout = 15_000
                }.getInputStream().bufferedReader().readText()
        ).jsonArray
    }

    /** Map a release [version] (e.g. `b1.7.3`) to the mcphackers
     *  artifact key (e.g. `b1.7`); unknown versions pass through. */
    fun artifactKey(version: String): String = try {
        manifest.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == version
        }?.jsonObject?.get("resources")?.jsonPrimitive?.content
            ?.substringAfterLast('/')?.removeSuffix(".zip")
            ?: version
    } catch (e: Exception) {
        version
    }
}
