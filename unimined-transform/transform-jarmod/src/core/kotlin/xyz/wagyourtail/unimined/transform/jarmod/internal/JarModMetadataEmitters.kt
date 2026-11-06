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
