package xyz.wagyourtail.unimined.mod.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Structural merge of a generated metadata document over a manually
 * authored one — used when a loader metadata file (e.g.
 * `fabric.mod.json`) already exists in the environment's resources.
 *
 * Semantics:
 * - objects merge recursively;
 * - arrays union element-wise, deduplicated, existing entries first;
 * - scalars: the generated value wins — the `mod {}`/`loader {}` DSL is
 *   the declared source of truth and can carry dynamic values the file
 *   cannot.
 *
 * Non-object roots fall back to the generated document.
 */
internal object MetadataJsonMerge {

    fun merge(existing: ByteArray, generated: ByteArray): ByteArray {
        val base = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(
                existing.decodeToString()
            )
        }.getOrNull() ?: return generated
        val overlay = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(
                generated.decodeToString()
            )
        }.getOrNull() ?: return generated
        if (base !is JsonObject || overlay !is JsonObject) return generated
        return mergeObjects(base, overlay).toString().toByteArray()
    }

    private fun mergeObjects(base: JsonObject, overlay: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in base) out[key] = value
        for ((key, value) in overlay) {
            val prev = out[key]
            out[key] = when {
                prev == null -> value
                prev is JsonObject && value is JsonObject ->
                    mergeObjects(prev, value)
                prev is JsonArray && value is JsonArray ->
                    JsonArray((prev + value).distinct())
                else -> value
            }
        }
        return JsonObject(out)
    }

    /** Merge two `java.util.jar.Manifest` bodies — generated attributes
     *  win; sections union by name with generated section attributes
     *  winning per key. */
    fun mergeManifests(existing: ByteArray, generated: ByteArray): ByteArray {
        val base = java.util.jar.Manifest(existing.inputStream())
        val over = java.util.jar.Manifest(generated.inputStream())
        val merged = java.util.jar.Manifest()
        merged.mainAttributes.putAll(base.mainAttributes)
        merged.mainAttributes.putAll(over.mainAttributes)
        for ((name, attrs) in base.entries) {
            merged.entries.getOrPut(name) { java.util.jar.Attributes() }
                .putAll(attrs)
        }
        for ((name, attrs) in over.entries) {
            merged.entries.getOrPut(name) { java.util.jar.Attributes() }
                .putAll(attrs)
        }
        val out = java.io.ByteArrayOutputStream()
        merged.write(out)
        return out.toByteArray()
    }

}
