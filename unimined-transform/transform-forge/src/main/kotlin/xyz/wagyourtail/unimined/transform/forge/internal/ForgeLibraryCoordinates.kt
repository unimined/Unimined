package xyz.wagyourtail.unimined.transform.forge.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.metadata.PlatformInfo
import xyz.wagyourtail.unimined.minecraft.metadata.VersionLibraryRules

/**
 * Structured `libraries[]` projection for forge/loader metadata.
 *
 * FG2 `dev.json`/`version.json` entries carry the Mojang object shape
 * (`rules`, `natives`, `downloads`), while FG3+ userdev `config.json`
 * generally uses bare coordinate strings. Flattening objects directly to
 * their `name` loses platform rules and turns natives-only entries into
 * unsatisfiable plain-jar dependencies; this projection preserves the
 * version-JSON semantics before environment rewrite/filter rules run.
 */
internal object ForgeLibraryCoordinates {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Plain classpath coordinates declared by [document], platform-filtered
     * and with natives-only aggregate entries removed.
     */
    fun classpathCoords(
        document: JsonObject,
        platform: PlatformInfo = PlatformInfo.current()
    ): List<String> =
        (document["libraries"] as? JsonArray).orEmpty().mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                is JsonObject -> runCatching {
                    json.decodeFromJsonElement(
                        VersionJson.Library.serializer(), entry
                    )
                }.getOrNull()?.let {
                    VersionLibraryRules.classpathCoordinate(it, platform)
                }
                else -> null
            }
        }.distinct()

}
