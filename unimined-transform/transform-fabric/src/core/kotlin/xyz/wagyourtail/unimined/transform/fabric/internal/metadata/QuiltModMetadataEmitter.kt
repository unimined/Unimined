package xyz.wagyourtail.unimined.transform.fabric.internal.metadata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * `quilt.mod.json` emitter — serves `loader("quilt")` environments.
 * Quilt is a fabric-loader fork but its metadata is a different
 * document: `schema_version` at root, the mod description nested
 * under `quilt_loader{}` (with `metadata{}` carrying name/desc/
 * authors/license/icon), `mixin`/`access_widener` top-level, user
 * `custom` entries emitted as top-level namespaced keys (the real
 * qmj extension convention — `modmenu`, `fabric-api:*`), and
 * interface injections under a top-level `quilt_loom{}` object —
 * the only key quilt-loom's dep reader consumes.
 *
 * Environment lives at `minecraft.environment` (not a valid
 * `quilt_loader` key) with quilt's own vocabulary — fmj `server`
 * becomes `dedicated_server`.
 */
class QuiltModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "quilt"

    override val metadataFileName = "quilt.mod.json"

    /** `environment` vocab — fmj `server` has no quilt equivalent;
     *  `dedicated_server` is the qmj keyword. `*`/`client` pass
     *  through. */
    private fun qmjEnvironment(env: String) =
        if (env == "server") "dedicated_server" else env

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val resolved = resolveEmit(context)
        val json = buildJsonObject {
            put("schema_version", 1)
            putJsonObject("quilt_loader") {
                // `group` is REQUIRED by quilt-loader's reader — the
                // maven coordinate of the mod's artifact
                put("group", context.mavenGroup ?: "unspecified")
                put("id", meta.id!!)
                put("version", meta.version ?: "0.0.0")
                putJsonObject("metadata") {
                    meta.name?.let { put("name", it) }
                    meta.description?.let { put("description", it) }
                    if (meta.authors.isNotEmpty()) {
                        putJsonObject("contributors") {
                            meta.authors.forEach { put(it, "Owner") }
                        }
                    }
                    meta.url?.let { url ->
                        putJsonObject("contact") { put("homepage", url) }
                    }
                    meta.license?.let { put("license", it) }
                    meta.icon?.let { put("icon", it) }
                }
                if (resolved.entrypoints.isNotEmpty()) {
                    putJsonObject("entrypoints") {
                        resolved.entrypoints.forEach { (kind, classes) ->
                            putJsonArray(kind) {
                                classes.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                }
                if (meta.depends.isNotEmpty()) {
                    putJsonArray("depends") {
                        meta.depends.forEach { (modId, range) ->
                            add(buildJsonObject {
                                put("id", modId)
                                put("versions", range)
                            })
                        }
                    }
                }
                // `intermediate_mappings` declares the jar's shipped
                // mapping set — quilt mods ride fabric intermediary
                context.prodNamespaces.singleOrNull()?.let {
                    if (FabricNamespaces.canonical(
                            it, context.namedNamespaces + context.devNamespace
                        ) == "intermediary"
                    ) {
                        put("intermediate_mappings", "net.fabricmc:intermediary")
                    }
                }
            }
            // `mixin`/`access_widener` are top-level qmj keys
            if (resolved.mixinRefs.isNotEmpty()) {
                putJsonArray("mixin") {
                    resolved.mixinRefs.forEach { add(JsonPrimitive(it)) }
                }
            }
            resolved.accessRef?.let { put("access_widener", it) }
            resolved.environment?.let {
                putJsonObject("minecraft") {
                    put("environment", qmjEnvironment(it))
                }
            }
            // qmj's extension mechanism is arbitrary top-level keys —
            // fmj `custom` entries land there verbatim
            resolved.sectionCustom.forEach { (key, value) -> put(key, value) }
            if (resolved.injections.isNotEmpty()) {
                putJsonObject("quilt_loom") {
                    put("injected_interfaces", JsonObject(
                        resolved.injections.toSortedMap()
                            .mapValues { (_, ifaces) ->
                                JsonArray(ifaces.sorted()
                                    .map { JsonPrimitive(it) })
                            }
                    ))
                }
            }
        }
        val emitted = mutableMapOf(
            metadataFileName to json.toString().toByteArray())
        emitManifest(context)?.let {
            emitted[ModMetadataEmitter.MANIFEST_PATH] = it
        }
        return emitted
    }

}
