package xyz.wagyourtail.unimined.transform.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Loader-agnostic access-declaration scan for dependency jars —
 * the union of `FabricModAnalysis`/`ForgeModAnalysis` discovery,
 * minus loader-metadata knowledge a loaderless (`common`)
 * environment can't possess:
 *
 * - headered AW/CT docs by entry-name convention
 *   (`*.accesswidener`, `*.aw`, `*.classtweaker`, `*.ct`) or by
 *   `accessWidener`/`classTweaker` first-line sniff, plus
 *   `fabric.mod.json` `accessWidener`/`classTweakers` references —
 *   ride `dependencyClassTweakerFiles` (only their `transitive-*`
 *   rules cross to dependents, loom parity)
 * - headerless AT files — `META-INF/accesstransformer.cfg`,
 *   `META-INF/…_at.cfg`, `FMLAT` manifest attribute — apply in
 *   full by FML semantics
 * - `custom.*injected_interfaces` keys in `fabric.mod.json`/
 *   `quilt.mod.json` (the `loom:`/`quilt_loom.`/`steel:`
 *   conventions) — synthesized into an equivalent transitive
 *   classTweaker, mirroring the fabric wiring
 */
internal object DepAccessScan {

    private val json = Json { ignoreUnknownKeys = true }

    /** Files a single dep jar declared, split by application channel. */
    data class Result(
        /** Headered AW/CT docs (+ synthesized II CTs) — dep channel. */
        val declared: List<Path>,
        /** Headerless AT docs — unconditional (FML semantics). */
        val ats: List<Path>,
    )

    /**
     * Extract [jar]'s access declarations into [cacheDir] and
     * return them. [depNs] is the environment's declared mod
     * source namespace — needed to bind headerless ATs and to head
     * the synthesized II doc; when absent those are skipped with a
     * [warn] since they can't be interpreted.
     */
    fun scan(
        jar: Path,
        cacheDir: Path,
        depNs: String?,
        warn: (String) -> Unit
    ): Result {
        val declared = ArrayList<Path>()
        val ats = ArrayList<Path>()
        val stem = jar.fileName.toString().removeSuffix(".jar")

        JarFile(jar.toFile()).use { jf ->
            fun copy(entryName: String): Path {
                val target = cacheDir.resolve(
                    "$stem-${entryName.substringAfterLast('/')}")
                if (Files.notExists(target)) {
                    Files.createDirectories(target.parent)
                    jf.getJarEntry(entryName)?.let { e ->
                        jf.getInputStream(e).use { input ->
                            Files.copy(input, target)
                        }
                    }
                }
                return target
            }

            // FMLAT manifest attribute — FML-era custom AT paths
            jf.manifest?.mainAttributes?.getValue("FMLAT")
                ?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { path ->
                    if (depNs == null) {
                        warn("${jar.fileName}: FMLAT-declared AT " +
                                "'$path' needs a mod source " +
                                "namespace to apply (declares none " +
                                "unambiguously) — skipping")
                    } else if (jf.getJarEntry(path) != null) {
                        ats.add(copy(path))
                    }
                }

            for (entry in jf.entries().toList()) {
                if (entry.isDirectory) continue
                val name = entry.name
                val lower = name.substringAfterLast('/').lowercase()
                when {
                    // headerless ATs — FML applies them to the shared
                    // artifact unconditionally
                    lower == "accesstransformer.cfg" ||
                        (lower.endsWith("_at.cfg") &&
                            name.startsWith("META-INF/")) -> {
                        if (depNs == null) {
                            warn("${jar.fileName}: AT '$name' needs " +
                                    "a mod source namespace to apply " +
                                    "(declares none unambiguously) " +
                                    "— skipping")
                        } else {
                            ats.add(copy(name))
                        }
                    }

                    // headered docs — unambiguous long extensions
                    lower.endsWith(".accesswidener") ||
                        lower.endsWith(".classtweaker") ->
                        declared.add(copy(name))

                    // short forms — header-sniff to avoid false hits
                    lower.endsWith(".aw") || lower.endsWith(".ct") -> {
                        val head = jf.getInputStream(entry).use { input ->
                            input.bufferedReader().readLine()
                                ?.trim() ?: ""
                        }
                        if (head.startsWith("accessWidener") ||
                            head.startsWith("classTweaker")
                        ) declared.add(copy(name))
                    }
                }
            }

            // fmj/qmj-declared docs — non-conventional paths +
            // `custom.*injected_interfaces` II synthesis
            for (metaName in listOf("fabric.mod.json", "quilt.mod.json")) {
                val entry = jf.getJarEntry(metaName) ?: continue
                val root = runCatching {
                    jf.getInputStream(entry).use { input ->
                        json.parseToJsonElement(
                            input.readBytes().decodeToString()
                        ).jsonObject
                    }
                }.getOrNull() ?: continue

                // declared file references whose names needn't follow
                // conventions (`"accessWidener": "foo.txt"`,
                // `"classTweakers"` object-keyed or array forms)
                root["accessWidener"]?.jsonPrimitive?.content
                    ?.let { path ->
                        if (jf.getJarEntry(path) != null) {
                            declared.add(copy(path))
                        }
                    }
                for (path in collectPaths(root["classTweakers"])) {
                    if (jf.getJarEntry(path) != null) {
                        declared.add(copy(path))
                    }
                }

                // `custom.*injected_interfaces` — target class →
                // injected interface internal names (dep prod ns)
                val injections =
                    mutableMapOf<String, MutableList<String>>()
                fun collectIi(obj: JsonObject) {
                    for ((key, value) in obj) {
                        if (key.endsWith("injected_interfaces") &&
                            value is JsonObject
                        ) {
                            for ((target, ifaces) in value) {
                                ifaces.jsonArray.forEach { el ->
                                    injections.getOrPut(target) {
                                        mutableListOf()
                                    }.add(el.jsonPrimitive.content)
                                }
                            }
                        } else if (value is JsonObject) {
                            collectIi(value)
                        }
                    }
                }
                (root["custom"] as? JsonObject)?.let(::collectIi)

                if (injections.isNotEmpty()) {
                    if (depNs == null) {
                        warn("${jar.fileName}: " +
                                "injected_interfaces needs a mod " +
                                "source namespace to apply " +
                                "(declares none unambiguously) " +
                                "— skipping")
                    } else {
                        val target = cacheDir.resolve(
                            "$stem-injected-interfaces.ct")
                        if (Files.notExists(target)) {
                            Files.createDirectories(target.parent)
                            val sb = StringBuilder(
                                "classTweaker\tv1\t$depNs\n")
                            for ((t, ifaces) in injections.toSortedMap()) {
                                for (iface in ifaces.sorted()) {
                                    sb.append("transitive-inject-" +
                                            "interface\t$t\t$iface\n")
                                }
                            }
                            Files.write(
                                target, sb.toString().toByteArray())
                        }
                        declared.add(target)
                    }
                }
            }
        }

        return Result(declared.distinct(), ats.distinct())
    }

    /** `classTweakers` may be an object keyed by path or an array of
     *  path entries — normalize to a path list. */
    private fun collectPaths(element: JsonElement?): List<String> =
        when (element) {
            null -> emptyList()
            is JsonObject -> element.keys.toList()
            else -> runCatching {
                element.jsonArray.map { it.jsonPrimitive.content }
            }.getOrDefault(emptyList())
        }

}
