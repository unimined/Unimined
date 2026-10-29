package xyz.wagyourtail.unimined.transform.forge.internal

import kotlinx.serialization.json.*
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ForgeInstallerProbe

/**
 * The packaging era of a resolved forge-family loader artifact —
 * probed once by [ForgeInstallerProbe] from the artifact's *shape*,
 * never from version strings (replaces 1.x's `ForgeVersion` enum +
 * `ForgeFiles` accept/deny sniffing).
 */
sealed interface ForgeProfile {

    /**
     * Jar-diff patches: the artifact contains patched
     * `net/minecraft/...` class files directly (FG1-era,
     * ≤1.6.x/universal-zips and the pre-1.3 sided zips).
     */
    data object ZipPatches : ForgeProfile

    /**
     * Release binpatches: `binpatches.pack.lzma` + `forge_at.cfg` /
     * `fml_at.cfg` + `version.json` (FG2-era, ~1.8–1.12.2-..2847).
     *
     * [devPackage] is the FG2 `userdev` artifact when it exists
     * (`dev.json` + `devbinpatches.pack.lzma` + `merged_at.cfg` +
     * `merged.srg`/`merged.exc` + `patches.zip` + `sources.zip`) — the
     * canonical dev package 1.x never consumed; the universal-jar path
     * remains the per-version fallback.
     */
    data class Binpatches(
        val devPackage: DevPackage?,
        /** `version.json` embedded in the universal jar — the
         *  pre-`userdev3` FG2 dev metadata. Same `mainClass`/
         *  `minecraftArguments`/`libraries` shape as `dev.json`
         *  (1.x `parseVersionJson`). */
        val versionJson: JsonObject? = null
    ) : ForgeProfile {

        /** `mainClass` — dev package wins, then `version.json`. */
        val mainClass: String?
            get() = devPackage?.mainClass ?: versionJson?.versionMainClass()

        /** Dev program args — dev package wins, then `version.json`'s
         *  `minecraftArguments`. */
        val programArgs: List<String>
            get() = devPackage?.programArgs?.takeIf { it.isNotEmpty() }
                ?: versionJson?.versionProgramArgs() ?: emptyList()

        /** Dev classpath deps — dev package wins, then `version.json`'s
         *  `libraries[].name` (launchwrapper, scala/akka, ...). */
        val libraries: List<String>
            get() = devPackage?.libraries
                ?: versionJson?.versionLibraries() ?: emptyList()

        /** Interpreted FG2 `userdev` jar contents (`dev.json` era —
         *  distinct format from FG3's `config.json`). */
        class DevPackage(
            /** The resolved `*:userdev` artifact. */
            val jar: java.nio.file.Path,
            /** Parsed `dev.json` — an `inheritsFrom` version JSON with
             *  authoritative `minecraftArguments` + dev libraries. */
            val devJson: JsonObject
        ) {

            /** `mainClass` — `net.minecraft.launchwrapper.Launch`. */
            val mainClass: String?
                get() = devJson.versionMainClass()

            /** `minecraftArguments` — the authoritative dev arg line
             *  (`--tweakClass <FMLTweaker> ...`), whitespace-tokenized. */
            val programArgs: List<String>
                get() = devJson.versionProgramArgs()

            /** `libraries[].name` — the dev classpath deps
             *  (launchwrapper, scala/akka, etc.). */
            val libraries: List<String>
                get() = devJson.versionLibraries()
        }
    }

    /**
     * Userdev-style installer config: `config.json` with
     * `data`/`functions` step definitions (FG3–6 — 1.x's "FG3" umbrella
     * which was never version-gated internally). Interpretation keys
     * off config fields, not MC versions.
     */
    data class Userdev(val config: UserdevConfig) : ForgeProfile
}

/**
 * The fields of a userdev `config.json` unimined consumes (1.x
 * `FG3MinecraftTransformer` parity); [json] retains the raw object for
 * fields not yet modeled (`data`/`functions`/`modules`/`runs`).
 */
class UserdevConfig(val json: JsonObject) {

    private fun str(key: String) =
        json[key]?.jsonPrimitive?.contentOrNull

    /** `mcp` artifact coordinate — `de.oceanlabs.mcp:mcp_config` or the
     *  neoform-family equivalent. */
    val mcp: String
        get() = str("mcp")
            ?: "de.oceanlabs.mcp:mcp_config@zip"

    /** Obfuscation namespace the userdev config targets —
     *  `notchObf`→official, neoform-family mcp→mojmap, else searge
     *  (1.x `obfNamespace`). */
    val obfNamespace: String
        get() =
            if (json["notchObf"]?.jsonPrimitive?.booleanOrNull == true) "official"
            else if (mcp.contains("neoform")) "mojmap"
            else "searge"

    /** `notchObf` — runtime is notch-obfuscated (pre-1.16.5-era). */
    val notchObf: Boolean
        get() =
            json["notchObf"]?.jsonPrimitive?.booleanOrNull == true

    /** `ats` — path prefix(es) inside the installer jar under which
     *  the userdev AT files live (1.x reads this as a single
     *  `startsWith` prefix; newer userdev declares a list —
     *  `fml_at.cfg`/`forge_at.cfg`/`META-INF/accesstransformer.cfg`
     *  sit beneath it). */
    val ats: List<String> get() = flattenArgs(json["ats"])

    /** `binpatches` data key (file inside the installer jar). */
    val binpatches: String? get() = str("binpatches")

    /** `binpatcher` — `{version, args}` tool spec run over
     *  [binpatches] (`binpatches.pack.lzma`) to produce the patched
     *  jar; args use `{clean}`/`{patch}`/`{output}` placeholders. */
    val binpatcher: ToolSpec?
        get() =
            (json["binpatcher"] as? JsonObject)?.let { obj ->
                ToolSpec(
                    obj["version"]?.jsonPrimitive?.contentOrNull
                        ?: error("userdev binpatcher has no version"),
                    flattenArgs(obj["args"])
                )
            }

    data class ToolSpec(val version: String, val args: List<String>)

    /** `patches` data key for the merged patch set. */
    val patches: String? get() = str("patches")

    val patchesOriginalPrefix: String
        get() =
            str("patchesOriginalPrefix") ?: "a/"
    val patchesModifiedPrefix: String
        get() =
            str("patchesModifiedPrefix") ?: "b/"

    /** `inject` prefix — strip paths under it when extracting
     *  `client-extra` content from the installer jar. */
    val inject: String? get() = str("inject")

    /** `universal` — the loader's universal-jar coordinate, shaded
     *  into the dev artifact (1.x `JarModMinecraftTransformer.transform`
     *  overlay): FML locates `forge-<mc>-<ver>` on the dev classpath
     *  and expects forge classes inside it, so the content must be
     *  in-jar on every era. */
    val universal: String? get() = str("universal")

    /** `universalFilters` — include-regexes over universal-jar entry
     *  names for the shade (e.g. `^(?!binpatches\.pack\.lzma$).*$`). */
    val universalFilters: List<String>
        get() = flattenArgs(json["universalFilters"])

    /** `libraries` — maven coordinates the dev environment needs. */
    val libraries: List<String>
        get() =
            json["libraries"]?.jsonArray?.mapNotNull {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: it.jsonPrimitive.contentOrNull
            } ?: emptyList()

    /** `runs` — per-side run config objects, keyed `client`/`server`/
     *  `data`. */
    val runs: Map<String, JsonObject>
        get() =
            json["runs"]?.jsonObject?.mapNotNull { (k, v) ->
                (v as? JsonObject)?.let { k to it }
            }?.toMap() ?: emptyMap()

    /** `data` — side-aware data entries (value may be a string or a
     *  `{client,server}` object — resolved by the consumer with the
     *  environment side). */
    /** `modules` — dep coords (`g:a:v[@jar]`) the dev runtime places on
     *  the module path; resolved against the env's mc libraries. */
    val modules: List<String>
        get() =
            (json["modules"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    val data: JsonObject? get() = json["data"] as? JsonObject

    /** `functions` — mcpconfig-style step definitions. */
    val functions: JsonObject? get() = json["functions"] as? JsonObject

    fun has(key: String): Boolean = json.containsKey(key)
    fun get(key: String): JsonElement? = json[key]

    companion object {

        /**
         * Flatten an arg array (`args`, `jvmArgs`, tool `args`) into
         * strings. Elements may be primitives, nested arrays (some
         * userdev versions group multi-token args), or
         * `{rules: [...], value: ...}` conditional objects — the rules
         * gate launcher features that don't apply to a dev run, so the
         * value is taken unconditionally (1.x `.asString` behaviour
         * made the same assumption, it just crashed on the shape).
         */
        fun flattenArgs(el: JsonElement?): List<String> = when (el) {
            null -> emptyList()
            is JsonPrimitive -> listOf(el.content)
            is JsonArray -> el.flatMap(::flattenArgs)
            is JsonObject -> flattenArgs(el["value"])
        }
    }
}

/** `mainClass` of a dev-metadata object (`dev.json`/`version.json`
 *  share this shape). */
internal fun JsonObject.versionMainClass(): String? =
    this["mainClass"]?.jsonPrimitive?.contentOrNull

/** `minecraftArguments` — whitespace-tokenized dev arg line. */
internal fun JsonObject.versionProgramArgs(): List<String> =
    this["minecraftArguments"]?.jsonPrimitive?.contentOrNull
        ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
        ?: emptyList()

/** `libraries[].name` — maven-coordinate dev classpath deps. */
internal fun JsonObject.versionLibraries(): List<String> =
    (this["libraries"] as? JsonArray)?.mapNotNull {
        (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
    } ?: emptyList()
