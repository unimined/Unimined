package xyz.wagyourtail.unimined.init

import java.io.File
import java.net.URI

/** Build-time version prober — emits ProbedVersions.kt into
 *  build/generated/probed (consumed by commonMain). Compiled in the
 *  `jvmProbedgen` compilation, independent of commonMain — a task
 *  running jvmMain classes would be a compile cycle. */
fun main(args: Array<String>) {
    val outDir = File(args[0])

    fun http(u: String) = URI(u).toURL().openConnection().run {
        setRequestProperty("User-Agent", "unimined-init")
        getInputStream().readBytes().decodeToString()
    }
    fun mavenVers(u: String, prefix: String? = null): List<String> {
        val vs = Regex("<version>([^<]+)</version>")
            .findAll(http(u)).map { it.groupValues[1] }.toList().reversed()
        return prefix?.let { p -> vs.filter { it.startsWith(p) } } ?: vs
    }
    fun fabricAll(kind: String): Map<String, List<String>> =
        Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\",[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(http("https://meta.fabricmc.net/v2/versions/$kind"))
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })

    fun compress(vs: List<String>): String {
        if (vs.isEmpty()) return ""
        val base = { s: String -> s.dropLastWhile { it.isDigit() } }
        val num = { s: String -> s.takeLastWhile { it.isDigit() }.toIntOrNull() }
        val segs = mutableListOf<String>()
        var start = vs[0]; var prev = vs[0]
        fun flush() { segs.add(
            if (start == prev) start
            else "${base(start)}${num(start)}-${num(prev)}") }
        for (v in vs.drop(1)) {
            if (base(v) == base(prev) && num(v) != null &&
                num(v) == num(prev)!! - 1) { prev = v; continue }
            flush(); start = v; prev = v
        }
        flush()
        return segs.joinToString(",")
    }

    val p = linkedMapOf<String, List<String>>()
    p["@mc"] = runCatching {
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(http("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
            .map { it.groupValues[1] }.toList()
    }.getOrDefault(emptyList())
    p["@plugin"] = runCatching {
        mavenVers("https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml")
    }.getOrDefault(emptyList())

    // fabric-meta all-versions: one fetch per kind, grouped by mc
    for ((kind, key) in listOf("yarn" to "yarn", "loader" to "fabric"))
        runCatching { fabricAll(kind) }.getOrDefault(emptyMap())
            .forEach { (mc, vs) -> p["$key:$mc"] = vs }

    // ornithe meta: { "<mapping>": [{version, gameVersion, ...}], ... }
    runCatching {
        val json = http("https://meta.ornithemc.net/v3/versions")
        for (name in listOf("feather", "raven", "nests", "sparrow", "osl")) {
            Regex("\"$name\"\\s*:\\s*\\[(.*?)]").find(json)?.let { sec ->
                Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\"[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(sec.groupValues[1])
                    .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                    .forEach { (mc, vs) -> p["$name:$mc"] = vs }
            }
        }
    }

    // mojmap: published since 19w36a (1.15 cycle) — filter the @mc list
    p["mojmap"] = p["@mc"].orEmpty().filter { v ->
        if (!v[0].isDigit()) false
        else if (v.contains("w")) {
            val w = v.substringBefore("w").toIntOrNull() ?: 0
            val b = v.substringAfter("w").filter { it.isDigit() }
                .toIntOrNull() ?: 0
            w > 19 || (w == 19 && b >= 36)
        } else {
            val minor = v.split(".").getOrNull(1)?.toIntOrNull() ?: 0
            minor >= 15 || v == "1.14.4"
        }
    }

    for ((k, u, pre) in listOf(
        Triple("quilt", "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml", null),
        Triple("neoforge", "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", null),
        Triple("stationapi", "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/maven-metadata.xml", null),
        Triple("osl", "https://maven.ornithemc.net/releases/net/ornithemc/osl/maven-metadata.xml", null),
        Triple("quilt-mappings", "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/maven-metadata.xml", null)))
        p[k] = runCatching { mavenVers(u, pre) }.getOrDefault(emptyList())
    p["forge"] = runCatching {
        mavenVers("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
    }.getOrDefault(emptyList())

    // mcp index: {"1.12.2": {"snapshot": [v,...], "stable": [v,...]}}
    runCatching {
        val json = http(
            "https://maven.minecraftforge.net/releases/de/oceanlabs/mcp/versions.json")
        for (e in Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}").findAll(json)) {
            p["mcp:${e.groupValues[1]}"] =
                listOf("stable", "snapshot").flatMap { ch ->
                    Regex("\"$ch\"\\s*:\\s*\\[([^]]*)]")
                        .find(e.groupValues[2])?.groupValues?.get(1)
                        ?.let { l -> Regex("[0-9]+").findAll(l)
                            .map { "${ch}_${it.groupValues[0]}" }.toList() }
                        ?: emptyList()
                }
        }
    }.onFailure {
        p["mcp:1.7.10"] = listOf("stable_12")
        p["mcp:1.12.2"] = listOf("stable_39")
    }

    val f = outDir.resolve("ProbedVersions.kt")
    f.parentFile.mkdirs()
    f.writeText(buildString {
        appendLine("package xyz.wagyourtail.unimined.init")
        appendLine()
        appendLine("/** Generated by genProbedVersions — compressed run lists. */")
        appendLine("object Probed {")
        appendLine("    val MAP = mapOf(")
        for ((k, vs) in p) appendLine("        \"$k\" to \"${compress(vs)}\",")
        appendLine("    )")
        appendLine("    fun expand(key: String): List<String> =")
        appendLine("        MAP[key]?.let { expandRuns(it) } ?: emptyList()")
        appendLine("    private val runRe = Regex(\"\"\"(.*?)(\\d+)-(\\d+)\"\"\")")
        appendLine("    private fun expandRuns(s: String): List<String> =")
        appendLine("        if (s.isEmpty()) emptyList() else s.split(',').flatMap { seg ->")
        appendLine("            val m = runRe.matchEntire(seg.trim())")
        appendLine("            if (m != null) {")
        appendLine("                val (base, hi, lo) = m.destructured")
        appendLine("                (hi.toInt() downTo lo.toInt()).map { base + it }")
        appendLine("            } else listOf(seg.trim())")
        appendLine("        }")
        appendLine("}")
    })
}
