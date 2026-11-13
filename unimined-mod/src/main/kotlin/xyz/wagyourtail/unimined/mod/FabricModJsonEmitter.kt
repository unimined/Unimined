package xyz.wagyourtail.unimined.mod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * Shared `fabric.mod.json` emitter for the fabric-loader document shape
 * (fabric itself plus fabric-fork loaders like ornithe/babric). Loader
 * subclasses only declare their [ModMetadataEmitter.loader] name and
 * may override the loader-specific metadata keys below.
 *
 * Emission covers the loader-neutral model plus host-discovered data:
 * generated *and* resource-discovered mixin configs land in `mixins`,
 * [ModMetadataEmitter.gametestInterface] implementors land under
 * [gametestEntrypointKey], generated classTweaker files land in
 * `classTweakers`, and mixin-derived interface injections land in
 * `custom.<[injectedInterfacesKey]>`.
 */
abstract class FabricModJsonEmitter : ModMetadataEmitter {

    /** Fabric-family loaders consume access wideners — env-declared
     *  access files convert to AW at `<modid>.accesswidener`. */
    override val accessFormat = "aw"
    override val accessPath = "{modid}.accesswidener"

    /**
     * `custom` key carrying compile-visible interface injections
     * (`loom:injected_interfaces` on fabric-flavored loaders). The
     * emitted value maps target class internal names to the interface
     * internal names mixins inject onto them, in the dev namespace —
     * the prod repack rebases the keys to the production namespace.
     */
    protected open val injectedInterfacesKey = "loom:injected_interfaces"

    /**
     * The emitted descriptor path — `fabric.mod.json` for
     * fabric-loader forks, `quilt.mod.json` on quilt.
     */
    protected open val metadataFileName = "fabric.mod.json"

    /** Entrypoint kind receiving [ModMetadataEmitter.EmitContext.gametestEntrypoints]. */
    protected open val gametestEntrypointKey = "fabric-gametest"

    /** Entrypoint kind receiving [ModMetadataEmitter.EmitContext.clientGametestEntrypoints]. */
    protected open val clientGametestEntrypointKey = "fabric-client-gametest"

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val section = meta.sections[loader]
        val mixinRefs = (context.mixinFiles + context.discoveredMixinConfigs +
                section?.mixinConfigs.orEmpty()).distinct()
        val env = section?.environment ?: meta.environment

        // declared entrypoints union discovered gametest classes —
        // dedupe preserves declaration order
        val entrypoints = LinkedHashMap<String, List<String>>()
        section?.entrypoints?.forEach { (kind, classes) ->
            entrypoints[kind] = classes
        }
        if (context.gametestEntrypoints.isNotEmpty()) {
            entrypoints[gametestEntrypointKey] =
                (entrypoints[gametestEntrypointKey].orEmpty() +
                        context.gametestEntrypoints).distinct()
        }
        if (context.clientGametestEntrypoints.isNotEmpty()) {
            entrypoints[clientGametestEntrypointKey] =
                (entrypoints[clientGametestEntrypointKey].orEmpty() +
                        context.clientGametestEntrypoints).distinct()
        }

        val injections = context.interfaceInjections
        val custom = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        section?.custom?.forEach { (key, value) ->
            custom[key] = JsonPrimitive(value.toString())
        }
        if (injections.isNotEmpty()) {
            // a hand-written fmj carrying the same key merges at the
            // physical-file level (MetadataJsonMerge) — per-target
            // interface arrays union there
            custom[injectedInterfacesKey] = JsonObject(
                injections.toSortedMap().mapValues { (_, ifaces) ->
                    JsonArray(ifaces.sorted().map { JsonPrimitive(it) })
                }
            )
        }

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
            if (context.classTweakerRefs.isNotEmpty()) {
                putJsonArray("classTweakers") {
                    context.classTweakerRefs.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (meta.depends.isNotEmpty()) {
                putJsonObject("depends") {
                    meta.depends.forEach { (modId, range) -> put(modId, range) }
                }
            }
            (section?.accessWidener ?: context.accessFile)
                ?.let { put("accessWidener", it) }
            if (custom.isNotEmpty()) {
                putJsonObject("custom") {
                    custom.forEach { (key, value) -> put(key, value) }
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

    /**
     * 1.x `jarTask.manifest` parity — fabric-family production jars
     * carried fingerprint attrs; loader-contributed extras
     * (`Calamus-Generation`, `Legacy-Fabric-Intermediary-Version`)
     * merge verbatim. Env facts only — no `metadata` reads.
     */
    override fun emitManifest(context: ModMetadataEmitter.EmitContext): ByteArray? {
        if (context.loaderVersion == null &&
            context.manifestAttributes.isEmpty() &&
            context.prodNamespaces.isEmpty()
        ) return null
        val attrs = buildList {
            add("Manifest-Version" to "1.0")
            add("Fabric-Minecraft-Version" to context.minecraftVersion)
            context.loaderVersion?.let {
                add("Fabric-Loader-Version" to it)
            }
            // the shipped artifact's namespace, in fabric's
            // canonical official/intermediary/named vocabulary —
            // single-prod-ns envs only (ambiguous otherwise, and
            // absent is the safe default). devNamespace is
            // named-role even when unflagged.
            context.prodNamespaces.singleOrNull()?.let {
                add("Fabric-Mapping-Namespace" to FabricNamespaces.canonical(
                    it, context.namedNamespaces + context.devNamespace))
            }
            context.manifestAttributes.forEach { (k, v) -> add(k to v) }
        }
        return attrs.joinToString(
            "\r\n", postfix = "\r\n\r\n"
        ) { (k, v) -> "$k: $v" }.toByteArray(Charsets.UTF_8)
    }

}
