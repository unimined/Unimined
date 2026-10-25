package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed view over a (possibly merged) version JSON document. Every field
 * is tolerant of absence so loader-merged documents of any era — legacy
 * `minecraftArguments` through modern `arguments` — read uniformly.
 */
class VersionData private constructor(private val document: JsonObject) {

    val id: String? get() = document.string("id")
    val type: String? get() = document.string("type")
    val mainClass: String? get() = document.string("mainClass")
    val assets: String? get() = document.string("assets")
    val inheritsFrom: String? get() = document.string("inheritsFrom")
    val minimumLauncherVersion: Int? get() = document.int("minimumLauncherVersion")
    val complianceLevel: Int? get() = document.int("complianceLevel")

    /** `javaVersion.majorVersion`, or null when the document omits it. */
    val javaMajorVersion: Int?
        get() = document.obj("javaVersion")?.int("majorVersion")

    /** Asset index descriptor (`assetIndex` object), when present. */
    val assetIndex: AssetRef?
        get() = document.obj("assetIndex")?.let { ref ->
            AssetRef(
                id = ref.string("id"),
                url = ref.string("url"),
                sha1 = ref.string("sha1"),
                totalSize = ref.long("totalSize")
            )
        }

    /** Jar downloads by name (`downloads.client`, `downloads.server`, ...). */
    fun download(name: String): AssetRef? =
        document.obj("downloads")?.obj(name)?.let { ref ->
            AssetRef(
                id = ref.string("id"),
                url = ref.string("url"),
                sha1 = ref.string("sha1"),
                totalSize = ref.long("size")
            )
        }

    /** Modern `arguments.game`/`arguments.jvm` entries, or null when
     *  the document uses the legacy `minecraftArguments` string. */
    val gameArguments: List<ArgumentEntry>?
        get() = argumentsBlock("game")

    val jvmArguments: List<ArgumentEntry>?
        get() = argumentsBlock("jvm")

    private fun argumentsBlock(section: String): List<ArgumentEntry>? =
        document.obj("arguments")?.array(section)?.map { ArgumentEntry.parse(it) }

    /** Legacy single-string game arguments (pre-1.13 documents). */
    val minecraftArguments: String? get() = document.string("minecraftArguments")

    /** Declared libraries in document order. */
    val libraries: List<Library>
        get() = (document.array("libraries") ?: emptyList()).mapNotNull { element ->
            val lib = element as? JsonObject ?: return@mapNotNull null
            val name = lib.string("name") ?: return@mapNotNull null
            Library(
                name = name,
                rules = lib.rules(),
                natives = lib.obj("natives")?.entries
                    ?.associate { (k, v) -> k to v.jsonPrimitive.content },
                extractExclude = lib.obj("extract")?.stringList("exclude") ?: emptyList(),
                downloads = lib.obj("downloads")?.entries
                    ?.associate { (k, v) -> k to v.jsonObject }
                    ?: emptyMap()
            )
        }

    /** The logging config block, when the document declares one. */
    val logging: JsonObject? get() = document.obj("logging")

    /** A `path`/`url`/`sha1`-style object reference. */
    class AssetRef(val id: String?, val url: String?, val sha1: String?, val totalSize: Long?)

    /** One `arguments` list entry — either a bare value or a rules-gated
     *  object of the form `{ "rules": [...], "value": "..." | [...] }`. */
    class ArgumentEntry(val value: List<String>, val rules: List<Rule>) {

        /** Whether this entry applies for [os] and the enabled [features]. */
        fun appliesTo(os: OperatingSystem, features: Set<String>): Boolean =
            RuleSet.allowed(rules, os, features)

        companion object {
            fun parse(element: JsonElement): ArgumentEntry = when (element) {
                is JsonPrimitive -> ArgumentEntry(listOf(element.content), emptyList())
                is JsonObject -> ArgumentEntry(
                    value = when (val value = element["value"]) {
                        is JsonPrimitive -> listOf(value.content)
                        is JsonArray -> value.map { it.jsonPrimitive.content }
                        else -> emptyList()
                    },
                    rules = element.rules()
                )
                else -> ArgumentEntry(emptyList(), emptyList())
            }
        }

    }

    /** A version-JSON `libraries` entry. */
    class Library(
        /** Maven coordinate `group:name:version`. */
        val name: String,
        val rules: List<Rule>,
        /** OS-key → natives classifier (may contain `${arch}`). */
        val natives: Map<String, String>?,
        val extractExclude: List<String>,
        /** Raw `downloads` object (`artifact`, `classifiers`, ...). */
        val downloads: Map<String, JsonObject>
    ) {

        /** Whether this library applies on [os] with [features]. */
        fun appliesTo(os: OperatingSystem, features: Set<String>): Boolean =
            RuleSet.allowed(rules, os, features)

        /** Natives classifier coordinate suffix for [os], or null. */
        fun nativeClassifier(os: OperatingSystem): String? =
            natives?.get(os.key)?.replace("\${arch}", OperatingSystem.jvmArch())

    }

    /** A single `rules` entry: `action` + optional `os`/`features` gates. */
    class Rule(
        val action: String,
        val osName: String?,
        val osArch: String?,
        val osVersion: String?,
        val features: Map<String, Boolean>
    ) {

        val allow: Boolean get() = action == "allow"

        fun matches(os: OperatingSystem, enabledFeatures: Set<String>): Boolean {
            if (osName != null && osName != os.key) return false
            if (osArch != null) {
                val arch = System.getProperty("os.arch").lowercase()
                if (osArch == "x86" && (arch == "x86_64" || arch == "amd64")) return false
                if (osArch != "x86" && !arch.startsWith(osArch.lowercase())) return false
            }
            if (osVersion != null &&
                !System.getProperty("os.version", "").matches(Regex(osVersion))) return false
            for ((feature, required) in features) {
                if (enabledFeatures.contains(feature) != required) return false
            }
            return true
        }

    }

    companion object {

        /** Parse a document into the typed view. */
        fun parse(document: JsonObject): VersionData = VersionData(document)

        internal fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        internal fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

        internal fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

        internal fun JsonObject.stringList(key: String): List<String> =
            array(key)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        internal fun JsonObject.rules(): List<Rule> =
            (this["rules"] as? JsonArray)?.mapNotNull { element ->
                val rule = element as? JsonObject ?: return@mapNotNull null
                val os = rule["os"] as? JsonObject
                val features = (rule["features"] as? JsonObject)?.entries
                    ?.associate { (k, v) -> k to v.jsonPrimitive.content.toBoolean() }
                    ?: emptyMap()
                Rule(
                    action = rule.string("action") ?: "allow",
                    osName = os?.string("name"),
                    osArch = os?.string("arch"),
                    osVersion = os?.string("version"),
                    features = features
                )
            } ?: emptyList()

    }

}
