package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed model of a (possibly chain-merged) version JSON document. Every
 * field is tolerant of absence so loader-merged documents of any era —
 * legacy `minecraftArguments` through modern `arguments` — read
 * uniformly.
 */
@Serializable
class VersionData(
    val id: String? = null,
    val type: String? = null,
    val mainClass: String? = null,
    val assets: String? = null,
    val inheritsFrom: String? = null,
    val minimumLauncherVersion: Int? = null,
    val complianceLevel: Int? = null,
    val javaVersion: JavaVersion? = null,
    val assetIndex: AssetRef? = null,
    val downloads: Map<String, AssetRef> = emptyMap(),
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null,
    val libraries: List<Library> = emptyList(),
    /** The logging config block — opaque passthrough to launch args. */
    val logging: JsonObject? = null
) {

    /** `javaVersion.majorVersion`, or null when the document omits it. */
    val javaMajorVersion: Int? get() = javaVersion?.majorVersion

    /** Modern `arguments.game` entries, or null on legacy documents. */
    val gameArguments: List<ArgumentEntry>? get() = arguments?.game

    /** Modern `arguments.jvm` entries, or null on legacy documents. */
    val jvmArguments: List<ArgumentEntry>? get() = arguments?.jvm

    /** Jar download for [name] (`client`, `server`, ...), when present. */
    fun download(name: String): AssetRef? = downloads[name]

    /** `javaVersion` block. */
    @Serializable
    class JavaVersion(
        val component: String? = null,
        val majorVersion: Int? = null
    )

    /** A `path`/`url`/`sha1`/`size`/`totalSize` download descriptor. */
    @Serializable
    class AssetRef(
        val id: String? = null,
        val url: String? = null,
        val sha1: String? = null,
        val size: Long? = null,
        val totalSize: Long? = null
    )

    /** The modern `arguments` block — `game` and `jvm` sections. A null
     *  section means the document omits it entirely (legacy fallback
     *  semantics), distinct from an explicitly empty list. */
    @Serializable
    class Arguments(
        val game: List<ArgumentEntry>? = null,
        val jvm: List<ArgumentEntry>? = null
    )

    /** One `arguments` list entry — either a bare value or a rules-gated
     *  object of the form `{ "rules": [...], "value": "..." | [...] }`. */
    @Serializable(with = ArgumentEntry.Serializer::class)
    class ArgumentEntry(val value: List<String>, val rules: List<Rule>) {

        /** Whether this entry applies for [os] and the enabled [features]. */
        fun appliesTo(os: OperatingSystem, features: Set<String>): Boolean =
            RuleSet.allowed(rules, os, features)

        object Serializer : KSerializer<ArgumentEntry> {
            override val descriptor: SerialDescriptor =
                JsonElement.serializer().descriptor

            override fun deserialize(decoder: Decoder): ArgumentEntry {
                val element = decoder.decodeSerializableValue(JsonElement.serializer())
                return when (element) {
                    is JsonPrimitive -> ArgumentEntry(listOf(element.content), emptyList())
                    is JsonObject -> ArgumentEntry(
                        value = when (val value = element["value"]) {
                            is JsonPrimitive -> listOf(value.content)
                            is JsonArray -> value.map { it.jsonPrimitive.content }
                            else -> emptyList()
                        },
                        rules = element["rules"]?.let {
                            Json.decodeFromJsonElement<List<Rule>>(it)
                        } ?: emptyList()
                    )
                    else -> throw SerializationException(
                        "unexpected arguments entry: $element"
                    )
                }
            }

            override fun serialize(encoder: Encoder, value: ArgumentEntry) {
                throw UnsupportedOperationException(
                    "ArgumentEntry serialization is not supported"
                )
            }
        }

    }

    /** A version-JSON `libraries` entry. */
    @Serializable
    class Library(
        /** Maven coordinate `group:name:version`. */
        val name: String,
        val rules: List<Rule> = emptyList(),
        /** OS-key → natives classifier (may contain `${arch}`). */
        val natives: Map<String, String>? = null,
        val extract: Extract? = null,
        /** Raw `downloads` object (`artifact`, `classifiers`, ...). */
        val downloads: Map<String, JsonObject> = emptyMap()
    ) {

        @Serializable
        class Extract(val exclude: List<String> = emptyList())

        val extractExclude: List<String> get() = extract?.exclude ?: emptyList()

        /** Whether this library applies on [os] with [features]. */
        fun appliesTo(os: OperatingSystem, features: Set<String>): Boolean =
            RuleSet.allowed(rules, os, features)

        /** Natives classifier coordinate suffix for [os], or null. */
        fun nativeClassifier(os: OperatingSystem): String? =
            natives?.get(os.key)?.replace("\${arch}", OperatingSystem.jvmArch())

    }

    /** A single `rules` entry: `action` + optional `os`/`features` gates. */
    @Serializable
    class Rule(
        val action: String = "allow",
        val os: OsRule? = null,
        val features: Map<String, Boolean> = emptyMap()
    ) {

        @Serializable
        class OsRule(
            val name: String? = null,
            val arch: String? = null,
            val version: String? = null
        )

        val allow: Boolean get() = action == "allow"

        fun matches(os: OperatingSystem, enabledFeatures: Set<String>): Boolean {
            val ruleOs = this.os
            if (ruleOs?.name != null && ruleOs.name != os.key) return false
            val ruleArch = ruleOs?.arch
            if (ruleArch != null) {
                val arch = System.getProperty("os.arch").lowercase()
                if (ruleArch == "x86" && (arch == "x86_64" || arch == "amd64")) return false
                if (ruleArch != "x86" && !arch.startsWith(ruleArch.lowercase())) return false
            }
            val ruleVersion = ruleOs?.version
            if (ruleVersion != null &&
                !System.getProperty("os.version", "").matches(Regex(ruleVersion))) return false
            for ((feature, required) in features) {
                if (enabledFeatures.contains(feature) != required) return false
            }
            return true
        }

    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /** Decode a (merged) document into the typed view. */
        fun parse(document: JsonObject): VersionData =
            json.decodeFromJsonElement(serializer(), document)

    }

}
