package xyz.wagyourtail.unimined.transform.conversion.internal

import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * FoxLoader manifest-attribute emitter — serves `loader("foxloader")`
 * environments. FoxLoader 2.0's `JavaLoadingPlugin`/`JavaModInfo` has no
 * metadata file at all: a mod jar's identity and entrypoint ride jar
 * manifest attributes (`ModId`, `ModMain`, `ModMixin`, `ModEnvironment`,
 * ...). The emitted manifest body rides [ModMetadataEmitter.MANIFEST_PATH]
 * to the environment jar's manifest merge.
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
            // ModMain names the class extending foxloader's `Mod` base —
            // the `main` entrypoint kind carries it, first entry wins
            // (FoxLoader takes exactly one)
            section?.entrypoints?.get("main")?.firstOrNull()?.let {
                add("ModMain" to it)
            }
            // ModMixin is a single config filename (absent → FoxLoader
            // falls back to `<ModId>.mixins.json`)
            (context.mixinFiles + section?.mixinConfigs.orEmpty())
                .firstOrNull()?.let { add("ModMixin" to it) }
            // loader-specific manifest attrs pass through verbatim —
            // `custom("For-FoxLoader-Version", ...)` & friends
            section?.custom?.forEach { (key, value) ->
                add(key to value.toString())
            }
        }
        val body = attrs.joinToString(
            "\r\n", postfix = "\r\n\r\n"
        ) { (k, v) -> "$k: $v" }
        return mapOf(
            ModMetadataEmitter.MANIFEST_PATH to body.toByteArray(Charsets.UTF_8)
        )
    }

}
