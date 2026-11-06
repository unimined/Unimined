package xyz.wagyourtail.unimined.transform.forge.internal

import kotlinx.serialization.json.*
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * Forge-family metadata emitters: `META-INF/mods.toml` (FG3-era 1.13+),
 * `META-INF/neoforge.mods.toml` (NeoForge), and `mcmod.info` for the
 * FG1/FG2-era where mods.toml doesn't exist. Era is decided from the
 * environment's Minecraft version at task execution, so one emitter
 * serves all forge versions.
 */
abstract class ForgeFamilyMetadataEmitter : ModMetadataEmitter {

    /** File name for the TOML descriptor — `mods.toml` for forge-family,
     *  `neoforge.mods.toml` for NeoForge. */
    open val tomlFileName = "mods.toml"

    /** Whether this loader ships an mcmod.info for legacy-era MC. */
    open val legacyInfo = true

    /** FG2-era and older: release lines <1.13, or pre-release schemes
     *  (a/b/c/i version prefixes). */
    protected fun legacyEra(mc: String): Boolean {
        if (mc.startsWith("1.")) {
            val minor = mc.removePrefix("1.").substringBefore('.').toIntOrNull()
                ?: return false
            return minor < 13
        }
        return mc.firstOrNull()?.let { it in charArrayOf('a', 'b', 'c', 'i') } == true
    }

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val out = mutableMapOf<String, ByteArray>()
        val section = meta.sections[loader]
        val mixinRefs = context.mixinFiles + section?.mixinConfigs.orEmpty()

        if (!legacyEra(context.minecraftVersion)) {
            out["META-INF/$tomlFileName"] = toml(meta, mixinRefs, context)
        }
        if (legacyInfo) {
            out["mcmod.info"] = mcmodInfo(meta, context)
        }
        return out
    }

    private fun toml(
        meta: xyz.wagyourtail.unimined.mod.ModMetadata,
        mixinRefs: List<String>,
        context: ModMetadataEmitter.EmitContext
    ): ByteArray = buildString {
        appendLine("modLoader=\"javafml\"")
        appendLine("loaderVersion=\"[1,)\"")
        appendLine("license=\"${meta.license ?: "All Rights Reserved"}\"")
        meta.url?.let { appendLine("displayURL=\"$it\"") }
        meta.icon?.let { appendLine("logoFile=\"$it\"") }
        if (meta.authors.isNotEmpty()) {
            appendLine("authors=\"${meta.authors.joinToString(", ")}\"")
        }
        meta.description?.let { appendLine("description='''${it}'''") }
        appendLine()
        appendLine("[[mods]]")
        appendLine("modId=\"${meta.id!!}\"")
        appendLine("version=\"${meta.version ?: "0.0.0"}\"")
        meta.name?.let { appendLine("displayName=\"$it\"") }
        appendLine()
        for (ref in mixinRefs) {
            appendLine("[[mixins]]")
            appendLine("config=\"$ref\"")
            appendLine()
        }
        for ((dep, range) in meta.depends) {
            appendLine("[[dependencies.${meta.id}]]")
            appendLine("modId=\"$dep\"")
            appendLine("mandatory=true")
            appendLine("versionRange=\"${range.ifEmpty { "[0,)" }}\"")
            appendLine("ordering=\"NONE\"")
            appendLine("side=\"BOTH\"")
            appendLine()
        }
    }.toByteArray()

    private fun mcmodInfo(
        meta: xyz.wagyourtail.unimined.mod.ModMetadata,
        context: ModMetadataEmitter.EmitContext
    ): ByteArray = buildJsonArray {
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
    }.toString().toByteArray()
}

/** `loader("forge")` — mods.toml / mcmod.info. */
class ForgeModMetadataEmitter : ForgeFamilyMetadataEmitter() {
    override val loader = "forge"
}

/** `loader("lexforge")` — same files as forge. */
class LexforgeModMetadataEmitter : ForgeFamilyMetadataEmitter() {
    override val loader = "lexforge"
}

/** `loader("neoforge")` — `META-INF/neoforge.mods.toml`, no mcmod.info
 *  (NeoForge never ran on pre-1.13 MC). */
class NeoforgeModMetadataEmitter : ForgeFamilyMetadataEmitter() {
    override val loader = "neoforge"
    override val tomlFileName = "neoforge.mods.toml"
    override val legacyInfo = false
}

/** `loader("cleanroom")` — same files as forge. */
class CleanroomModMetadataEmitter : ForgeFamilyMetadataEmitter() {
    override val loader = "cleanroom"
}
