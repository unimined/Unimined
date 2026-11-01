package xyz.wagyourtail.unimined.mapping.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import java.nio.file.Paths

/**
 * Task-serializable form of a [MaterializedMappingSource] — declarations
 * (and their `mapNamespace`/`named` flags) don't survive being
 * flattened to bare files, so bundle/remap tasks consume this manifest
 * alongside the extracted files and rebuild the materialized sources
 * at execution time. Hand-rolled JSON: the modules consuming this
 * manifest don't all apply the kotlinx.serialization compiler plugin.
 */
data class MaterializedSourceSpec(
    val files: List<String>,
    val formatHint: String?,
    val nsMap: Map<String, String>,
    val namedNamespaces: Set<String>,
    val attachments: Map<String, String>
) {

    companion object {

        /** Capture [m] into its manifest form. */
        fun of(m: MaterializedMappingSource) = MaterializedSourceSpec(
            files = m.files.map { it.toString() },
            formatHint = m.formatHint,
            nsMap = m.nsMap,
            namedNamespaces = m.namedNamespaces,
            attachments = m.attachments.mapValues { it.value.toString() }
        )

        /** Decode one manifest entry produced by [toJson]. */
        fun fromJson(el: JsonElement) = el.jsonObject.let { o ->
            MaterializedSourceSpec(
                files = o.getValue("files").jsonArray.map { it.jsonPrimitive.content },
                formatHint = o["formatHint"]?.jsonPrimitive?.content,
                nsMap = (o["nsMap"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }
                    ?: emptyMap(),
                namedNamespaces = (o["namedNamespaces"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                attachments = (o["attachments"] as? JsonObject)
                    ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            )
        }

    }

    /** Encode this entry as a [JsonObject] — see [fromJson]. */
    fun toJson(): JsonObject = buildMap {
        put("files", JsonArray(files.map { JsonPrimitive(it) }))
        formatHint?.let { put("formatHint", JsonPrimitive(it)) }
        if (nsMap.isNotEmpty()) put("nsMap", JsonObject(nsMap.mapValues { JsonPrimitive(it.value) }))
        if (namedNamespaces.isNotEmpty())
            put("namedNamespaces", JsonArray(namedNamespaces.map { JsonPrimitive(it) }))
        if (attachments.isNotEmpty())
            put("attachments", JsonObject(attachments.mapValues { JsonPrimitive(it.value) }))
    }.let(::JsonObject)

    /** Rebuild the materialized source — the declaration is a stub
     *  since every field that delegates to it is supplied explicitly. */
    fun materialize(): MaterializedMappingSource = RuntimeMaterializedMappingSource(
        declaration = MappingResolver.RawFileSource(Paths.get(files.first())),
        files = files.map(Paths::get),
        formatHint = formatHint,
        nsMap = nsMap,
        namedNamespaces = namedNamespaces,
        attachments = attachments.mapValues { Paths.get(it.value) }
    )

}
