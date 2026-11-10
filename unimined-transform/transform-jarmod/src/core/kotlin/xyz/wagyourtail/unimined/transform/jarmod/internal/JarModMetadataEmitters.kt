package xyz.wagyourtail.unimined.transform.jarmod.internal

import kotlinx.serialization.json.*
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * JarMod-family metadata emitters — FG1/FG2-era loaders carry
 * `mcmod.info` (no mods.toml before 1.13).
 */
abstract class JarModFamilyMetadataEmitter : ModMetadataEmitter {

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val json = buildJsonArray {
            add(buildJsonObject {
                put("modid", meta.id!!)
                meta.name?.let { put("name", it) }
                meta.description?.let { put("description", it) }
                put("version", meta.version ?: "0.0.0")
                put("mcversion", context.minecraftVersion)
                meta.url?.let { put("url", it) }
                meta.icon?.let { put("logoFile", it) }
                if (meta.authors.isNotEmpty()) {
                    putJsonArray("authorList") {
                        meta.authors.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (meta.depends.isNotEmpty()) {
                    putJsonArray("dependencies") {
                        meta.depends.keys.forEach { add(JsonPrimitive(it)) }
                    }
                }
            })
        }
        return mapOf("mcmod.info" to json.toString().toByteArray())
    }
}

/** `loader("jarmod")` — mcmod.info. */
class JarModModMetadataEmitter : JarModFamilyMetadataEmitter() {
    override val loader = "jarmod"
}

/** `loader("fg1")` — mcmod.info. */
class Fg1ModMetadataEmitter : JarModFamilyMetadataEmitter() {
    override val loader = "fg1"
}

/**
 * `loader("liteloader")` — `litemod.json`: LiteLoader's own manifest
 * (NOT mcmod.info — LiteLoader scans for this exact filename).
 * `revision` is the required numeric build counter; `dependsOn`/
 * `requiredAPIs` take mod ids/api ids.
 */
class LiteLoaderModMetadataEmitter : ModMetadataEmitter {
    override val loader = "liteloader"

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val json = buildJsonObject {
            put("name", meta.name ?: meta.id!!)
            put("version", meta.version ?: "0.0.0")
            put("mcversion", context.minecraftVersion)
            put("revision", meta.version?.toIntOrNull() ?: 1)
            meta.authors.firstOrNull()?.let { put("author", it) }
            meta.description?.let { put("description", it) }
            if (meta.depends.isNotEmpty()) {
                putJsonArray("dependsOn") {
                    meta.depends.keys.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
        return mapOf("litemod.json" to json.toString().toByteArray())
    }
}

/**
 * `loader("rift")` — `riftmod.json`: Rift's own manifest (dimdev's
 * 1.13-era loader). `id` is required; `entrypoints`/`mixins`/
 * `listeners` are the keys RiftLoader scans — emitted empty so a
 * bare project jar still reads as a valid riftmod.
 */
class RiftModMetadataEmitter : ModMetadataEmitter {
    override val loader = "rift"

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val json = buildJsonObject {
            put("id", meta.id!!)
            put("name", meta.name ?: meta.id)
            put("version", meta.version ?: "0.0.0")
            meta.description?.let { put("description", it) }
            if (meta.authors.isNotEmpty()) {
                putJsonArray("authors") {
                    meta.authors.forEach { add(JsonPrimitive(it)) }
                }
            }
            val declared = meta.sections[loader]?.entrypoints.orEmpty()
            putJsonObject("entrypoints") {
                putJsonArray("main") {
                    declared["main"].orEmpty()
                        .forEach { add(JsonPrimitive(it)) }
                }
                declared.filterKeys { it != "main" }.forEach { (kind, classes) ->
                    putJsonArray(kind) {
                        classes.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            if (meta.depends.isNotEmpty()) {
                putJsonObject("dependencies") {
                    meta.depends.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        return mapOf("riftmod.json" to json.toString().toByteArray())
    }
}
