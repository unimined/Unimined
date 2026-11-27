package xyz.wagyourtail.unimined.transform.conversion.internal

import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * FoxLoader manifest-attribute emitter — serves `loader("foxloader")`
 * environments. FoxLoader has no metadata file at all: a mod jar's
 * identity and entrypoint ride jar manifest attributes. The attribute
 * family is era-aware — FoxLoader 1.x (ReIndev < 2.9) consumes
 * `CommonMod`/`ClientMod`/`ServerMod` and `CommonMixin`, while FoxLoader
 * 2.x consumes `ModMain`/`ModMixin`. The emitted manifest body rides
 * [ModMetadataEmitter.MANIFEST_PATH] to the environment jar's manifest
 * merge.
 */
class FoxLoaderModMetadataEmitter : ModMetadataEmitter {

    override val loader = "foxloader"

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val section = meta.sections[loader]
        val env = section?.environment ?: meta.environment

        val attrs = buildList {
            add("Manifest-Version" to "1.0")
            add("ModId" to meta.id!!)
            meta.version?.let { add("ModVersion" to it) }
            meta.name?.let { add("ModName" to it) }
            meta.description?.let { add("ModDesc" to it) }
            if (meta.authors.isNotEmpty()) {
                add("ModAuthors" to meta.authors.joinToString(", "))
            }
            meta.url?.let { add("ModWebsite" to it) }
            meta.icon?.let { add("ModIcon" to it) }
            env?.let { add("ModEnvironment" to it) }
            // JavaLoadingPlugin.getModInfo REQUIRES this attr
            // ("*" or "2.*") — a jar without it silently returns null
            // from getModInfoHelper and never registers as a mod
            if (section?.custom?.keys?.none {
                    it.equals("For-FoxLoader-Version", ignoreCase = true)
                } != false) {
                add("For-FoxLoader-Version" to "*")
            }
            val entrypoints = section?.entrypoints.orEmpty()
            val mainEntrypoint = entrypoints["main"]?.firstOrNull()
            val mixinConfig = (context.mixinFiles + section?.mixinConfigs.orEmpty())
                .firstOrNull()
            if (foxLoader1(context.minecraftVersion)) {
                // FoxLoader 1.x (ReIndev < 2.9) predates ModMain/ModMixin:
                // identity, entrypoints, and mixins ride the sided
                // Common/Client/Server attribute family. The loader-neutral
                // `main` kind is FoxLoader's common entrypoint; `client` and
                // `server` kinds map to their sided attributes.
                (entrypoints["common"]?.firstOrNull() ?: mainEntrypoint)?.let {
                    add("CommonMod" to it)
                }
                entrypoints["client"]?.firstOrNull()?.let {
                    add("ClientMod" to it)
                }
                entrypoints["server"]?.firstOrNull()?.let {
                    add("ServerMod" to it)
                }
                mixinConfig?.let { add("CommonMixin" to it) }
            } else {
                // FoxLoader 2.x — ModMain names the class extending
                // foxloader's `Mod` base, first `main` entry wins.
                mainEntrypoint?.let { add("ModMain" to it) }
                // ModMixin is a single config filename (absent → FoxLoader
                // falls back to `<ModId>.mixins.json`)
                mixinConfig?.let { add("ModMixin" to it) }
            }
            // loader-specific manifest attrs pass through verbatim —
            // `custom("For-FoxLoader-Version", ...)` & friends
            section?.custom?.forEach { (key, value) ->
                add(key to value.toString())
            }
            // env-level attrs merge last — user override wins
            context.manifestAttributes.forEach { (k, v) -> add(k to v) }
        }
        val body = attrs.joinToString(
            "\r\n", postfix = "\r\n\r\n"
        ) { (k, v) -> "$k: $v" }
        return mapOf(
            ModMetadataEmitter.MANIFEST_PATH to body.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * ReIndev's dist version selects the FoxLoader era: the unified
     * FoxLoader 2.x `loader` artifact starts at ReIndev 2.9; every
     * earlier dist pairs with FoxLoader 1.x. FoxLoader itself is often
     * versionless in the DSL (resolved from the compatibility map at
     * dependency resolution), so the dist version is the only reliable
     * metadata-emission-time discriminator.
     */
    private fun foxLoader1(minecraftVersion: String): Boolean {
        val parts = minecraftVersion.split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major < 2 || (major == 2 && minor < 9)
    }

    /**
     * The metadata-free slice: `For-FoxLoader-Version` is a
     * loader requirement, not a mod claim, and env
     * `manifestAttributes` pass through regardless — everything
     * else here is metadata-driven and stays in [emit].
     */
    override fun emitManifest(context: ModMetadataEmitter.EmitContext): ByteArray? {
        val attrs = buildList {
            add("Manifest-Version" to "1.0")
            add("For-FoxLoader-Version" to "*")
            context.manifestAttributes.forEach { (k, v) -> add(k to v) }
        }
        return attrs.joinToString(
            "\r\n", postfix = "\r\n\r\n"
        ) { (k, v) -> "$k: $v" }.toByteArray(Charsets.UTF_8)
    }

}
