package xyz.wagyourtail.unimined.remap.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Production-jar footgun lint — post-remap validation of the emitted
 * artifact. Catches the packaging mistakes that compile fine but fail
 * at runtime: metadata keys pointing at files that were never packed,
 * entrypoint/mixin classes the jar doesn't contain, and a missing or
 * wrong remapped-namespace marker.
 *
 * Only unimined-produced jars are linted (the user's own production
 * artifact) — never third-party mod jars flowing through transforms,
 * whose metadata we can't assume.
 */
object JarLint {

    /** Manifest attribute stamped on remapped production jars —
     *  lets a jar advertise which namespace its classes are in. */
    const val NAMESPACE_ATTRIBUTE = "Unimined-Mapping-Namespace"

    data class Problem(val file: String, val message: String) {
        override fun toString() = "$file: $message"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stamp [NAMESPACE_ATTRIBUTE] into the jar's manifest — makes a
     * remapped artifact self-describing (loom parity:
     * `Fabric-Mapping-Namespace`). Runs after the pipeline so step
     * impls can't strip it.
     */
    fun stampNamespace(jar: Path, namespace: String) {
        java.nio.file.FileSystems.newFileSystem(jar).use { fs ->
            val mfPath = fs.getPath("META-INF/MANIFEST.MF")
            val manifest = if (java.nio.file.Files.exists(mfPath))
                java.nio.file.Files.newInputStream(mfPath).use(::Manifest)
            else Manifest()
            manifest.mainAttributes.putIfAbsent(
                java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0"
            )
            manifest.mainAttributes.putValue(NAMESPACE_ATTRIBUTE, namespace)
            mfPath.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.newOutputStream(mfPath).use { manifest.write(it) }
        }
    }

    /**
     * Lint [jar]; when [expectedNamespace] is non-null the manifest
     * must carry [NAMESPACE_ATTRIBUTE] equal to it.
     * @return findings — empty means clean.
     */
    fun lint(jar: Path, expectedNamespace: String? = null): List<Problem> {
        val problems = mutableListOf<Problem>()
        ZipFile(jar.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()

            if (expectedNamespace != null) {
                val marker = zip.getEntry("META-INF/MANIFEST.MF")?.let { e ->
                    zip.getInputStream(e).use { Manifest(it) }
                        .mainAttributes.getValue(NAMESPACE_ATTRIBUTE)
                }
                when {
                    marker == null -> problems += Problem(
                        "META-INF/MANIFEST.MF",
                        "missing $NAMESPACE_ATTRIBUTE marker — jar was " +
                                "not stamped by the remap pipeline"
                    )
                    marker != expectedNamespace -> problems += Problem(
                        "META-INF/MANIFEST.MF",
                        "$NAMESPACE_ATTRIBUTE is '$marker' but the jar was " +
                                "remapped to '$expectedNamespace'"
                    )
                }
            }

            for (name in names) when {
                name == "fabric.mod.json" -> lintFabric(zip, name, names, problems)
                name == "quilt.mod.json" -> lintQuilt(zip, name, problems)
                name == "mcmod.info" -> lintJsonFile(zip, name, problems)
                name.endsWith(".mixins.json") -> lintMixinConfig(zip, name, names, problems)
                name == "META-INF/mods.toml" ||
                        name == "META-INF/neoforge.mods.toml" ->
                    lintModsToml(zip, name, problems)
                name == "plugin.yml" || name == "bungee.yml" ||
                        name == "paper-plugin.yml" ->
                    lintPluginYml(zip, name, names, problems)
            }
        }
        return problems
    }

    private fun readJson(zip: ZipFile, entry: String): JsonObject? = runCatching {
        zip.getInputStream(zip.getEntry(entry))
            .use { json.parseToJsonElement(it.readBytes().decodeToString()) }
            as? JsonObject
    }.getOrNull()

    private fun entries(zip: ZipFile, entry: String): String =
        zip.getEntry(entry)?.let {
            zip.getInputStream(it).use { s -> s.readBytes().decodeToString() }
        } ?: ""

    /** `a.b.C` -> `a/b/C.class`; strips array/inner syntax the check
     *  can't resolve. */
    private fun classEntry(fqcn: String): String =
        fqcn.removeSuffix("[]").substringBefore('$')
            .replace('.', '/') + ".class"

    private fun lintFabric(
        zip: ZipFile, name: String, names: Set<String>,
        problems: MutableList<Problem>
    ) {
        val fmj = readJson(zip, name) ?: run {
            problems += Problem(name, "not valid JSON")
            return
        }
        for (key in listOf("id", "version")) {
            if (fmj[key] == null) problems += Problem(name, "missing required key '$key'")
        }
        (fmj["accessWidener"] as? JsonPrimitive)?.content?.let { aw ->
            if (aw !in names) problems += Problem(
                name, "accessWidener '$aw' is not in the jar"
            )
        }
        // mixins: ["a.mixins.json", {"config": "b.mixins.json"}]
        (fmj["mixins"] as? JsonArray)?.forEach { m ->
            val cfg = (m as? JsonPrimitive)?.content
                ?: (m as? JsonObject)?.get("config")?.jsonPrimitive?.content
            if (cfg != null && cfg !in names) problems += Problem(
                name, "mixin config '$cfg' is not in the jar"
            )
        }
        // nested jars: "jars": [{"file": "META-INF/jars/x.jar"}]
        (fmj["jars"] as? JsonArray)?.forEach { j ->
            val file = (j as? JsonObject)?.get("file")?.jsonPrimitive?.content
            if (file != null && file !in names) problems += Problem(
                name, "nested jar '$file' is not in the jar"
            )
        }
        // icon: "icon": "path.png" or {"16": "a.png", "32": "b.png"}
        when (val icon = fmj["icon"]) {
            is JsonPrimitive -> if (icon.content !in names) problems += Problem(
                name, "icon '${icon.content}' is not in the jar"
            )
            is JsonObject -> icon.values.forEach { v ->
                val p = v.jsonPrimitive.content
                if (p !in names) problems += Problem(name, "icon '$p' is not in the jar")
            }
            else -> {}
        }
        // entrypoints: {"main": ["a.B", {"adapter": "x", "value": "c.D"}]}
        (fmj["entrypoints"] as? JsonObject)?.forEach { (type, points) ->
            (points as? JsonArray)?.forEach { p ->
                val cls = (p as? JsonPrimitive)?.content
                    ?: (p as? JsonObject)?.get("value")?.jsonPrimitive?.content
                if (cls != null && classEntry(cls) !in names) problems += Problem(
                    name, "entrypoint '$type' class '$cls' is not in the jar"
                )
            }
        }
    }

    private fun lintQuilt(zip: ZipFile, name: String, problems: MutableList<Problem>) {
        val qmj = readJson(zip, name) ?: run {
            problems += Problem(name, "not valid JSON")
            return
        }
        if (qmj["quilt_loader"] !is JsonObject) problems += Problem(
            name, "missing required 'quilt_loader' object"
        )
    }

    private fun lintJsonFile(zip: ZipFile, name: String, problems: MutableList<Problem>) {
        runCatching {
            zip.getInputStream(zip.getEntry(name))
                .use { json.parseToJsonElement(it.readBytes().decodeToString()) }
        }.onFailure { problems += Problem(name, "not valid JSON") }
    }

    private fun lintMixinConfig(
        zip: ZipFile, name: String, names: Set<String>,
        problems: MutableList<Problem>
    ) {
        val cfg = readJson(zip, name) ?: run {
            problems += Problem(name, "not valid JSON")
            return
        }
        for (key in listOf("package", "mixins")) {
            if (cfg[key] == null) problems += Problem(
                name, "mixin config missing '$key'"
            )
        }
        val pkg = cfg["package"]?.jsonPrimitive?.content ?: return
        for (list in listOf("mixins", "client", "server")) {
            (cfg[list] as? JsonArray)?.forEach { m ->
                val cls = m.jsonPrimitive.content
                if (classEntry("$pkg.$cls") !in names) problems += Problem(
                    name, "mixin class '$pkg.$cls' is not in the jar"
                )
            }
        }
    }

    private fun lintModsToml(zip: ZipFile, name: String, problems: MutableList<Problem>) {
        val toml = entries(zip, name)
        for (key in listOf("modLoader", "license")) {
            if (!Regex("""(?m)^\s*$key\s*=""").containsMatchIn(toml)) problems += Problem(
                name, "missing required key '$key'"
            )
        }
    }

    private fun lintPluginYml(
        zip: ZipFile, name: String, names: Set<String>,
        problems: MutableList<Problem>
    ) {
        val yml = entries(zip, name)
        val main = Regex("""(?m)^main:\s*(\S+)\s*$""").find(yml)?.groupValues?.get(1)
        when {
            main == null -> problems += Problem(name, "missing required key 'main'")
            classEntry(main) !in names -> problems += Problem(
                name, "main class '$main' is not in the jar"
            )
        }
    }
}
