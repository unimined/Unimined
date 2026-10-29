package xyz.wagyourtail.unimined.transform.fabric.internal

import kotlinx.serialization.json.*
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("fabric")` environments. The
 * base [xyz.wagyourtail.unimined.mod.LoaderSection] already carries what
 * Fabric needs (entrypoints, mixin config refs, access widener, custom
 * keys); no fabric-specific section subclass yet.
 */
class FabricModMetadataEmitter : ModMetadataEmitter {

    override val loader = "fabric"

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val section = meta.sections["fabric"]
        val entrypoints = section?.entrypoints.orEmpty()
        val mixinRefs = context.mixinFiles + section?.mixinConfigs.orEmpty()
        val env = section?.environment ?: meta.environment

        val json = buildJsonObject {
            put("schemaVersion", 1)
            put("id", meta.id!!)
            put("version", meta.version ?: "0.0.0")
            meta.name?.let { put("name", it) }
            meta.description?.let { put("description", it) }
            if (meta.authors.isNotEmpty()) {
                putJsonArray("authors") { meta.authors.forEach { add(JsonPrimitive(it)) } }
            }
            meta.url?.let { url ->
                putJsonObject("contact") { put("homepage", url) }
            }
            meta.license?.let { put("license", it) }
            meta.icon?.let { put("icon", it) }
            env?.let { put("environment", it) }
            if (entrypoints.isNotEmpty()) {
                putJsonObject("entrypoints") {
                    entrypoints.forEach { (kind, classes) ->
                        putJsonArray(kind) {
                            classes.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
            if (mixinRefs.isNotEmpty()) {
                putJsonArray("mixins") { mixinRefs.forEach { add(JsonPrimitive(it)) } }
            }
            if (meta.depends.isNotEmpty()) {
                putJsonObject("depends") {
                    meta.depends.forEach { (modId, range) -> put(modId, range) }
                }
            }
            section?.accessWidener?.let { put("accessWidener", it) }
            if (!section?.custom.isNullOrEmpty()) {
                putJsonObject("custom") {
                    section!!.custom.forEach { (key, value) -> put(key, value.toString()) }
                }
            }
        }
        return mapOf("fabric.mod.json" to json.toString().toByteArray())
    }

}
