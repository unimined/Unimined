package xyz.wagyourtail.unimined.init

import java.io.File
import java.net.URI

/** Build-time version prober — emits ProbedVersions.kt into
 *  build/generated/probed (consumed by commonMain). Compiled in the
 *  `jvmProbedgen` compilation, independent of commonMain — a task
 *  running jvmMain classes would be a compile cycle.
 *
 *  Probe table is at the top; the engine below executes it. */

private fun http(u: String) = URI(u).toURL().openConnection().run {
    setRequestProperty("User-Agent", "unimined-init")
    getInputStream().readBytes().decodeToString()
}

sealed interface Probe {
    fun run(p: MutableMap<String, List<String>>)

    /** Plain maven-metadata.xml list, newest-first, optional prefix. */
    class Maven(val key: String, val url: String,
                val prefix: String? = null) : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            val vs = Regex("<version>([^<]+)</version>")
                .findAll(http(url)).map { it.groupValues[1] }
                .toList().reversed()
            p[key] = prefix?.let { pfx -> vs.filter { it.startsWith(pfx) } }
                ?: vs
        }
    }

    /** piston-meta version_manifest_v2 — all release ids under `@mc`. */
    object Minecraft : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            p["@mc"] = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(http(
                    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
                .map { it.groupValues[1] }.toList()
        }
    }

    /** fabric-meta `/v2/versions/<kind>` all-versions, keyed `<key>:<mc>`. */
    class FabricAll(val kind: String, val key: String) : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\",[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(http("https://meta.fabricmc.net/v2/versions/$kind"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                .forEach { (mc, vs) -> p["$key:$mc"] = vs }
        }
    }

    /** ornithe meta `/v3/versions` section, keyed `<name>:<mc>`. */
    class Ornithe(val name: String) : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            val json = http("https://meta.ornithemc.net/v3/versions")
            Regex("\"$name\"\\s*:\\s*\\[(.*?)]").find(json)?.let { sec ->
                Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\"[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(sec.groupValues[1])
                    .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                    .forEach { (mc, vs) -> p["$name:$mc"] = vs }
            }
        }
    }

    /** forge files mcp versions.json, keyed `mcp:<mc>` stable_/snapshot_. */
    object McpIndex : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            val json = http(
                "https://maven.minecraftforge.net/releases/de/oceanlabs/mcp/versions.json")
            for (e in Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}").findAll(json))
                p["mcp:${e.groupValues[1]}"] =
                    listOf("stable", "snapshot").flatMap { ch ->
                        Regex("\"$ch\"\\s*:\\s*\\[([^]]*)]")
                            .find(e.groupValues[2])?.groupValues?.get(1)
                            ?.let { l -> Regex("[0-9]+").findAll(l)
                                .map { "${ch}_${it.groupValues[0]}" }.toList() }
                            ?: emptyList()
                    }
        }
    }

    /** Derived from other probed keys. */
    class Computed(val key: String,
                   val f: (Map<String, List<String>>) -> List<String>) : Probe {
        override fun run(p: MutableMap<String, List<String>>) { p[key] = f(p) }
    }

    /** Constant fallback — always succeeds. */
    class Const(val key: String, vararg val vs: String) : Probe {
        override fun run(p: MutableMap<String, List<String>>) {
            p.putIfAbsent(key, vs.toList())
        }
    }
}

private val PROBES: List<Probe> = listOf(
    Probe.Minecraft,
    Probe.Maven("@plugin",
        "https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml"),
    Probe.FabricAll("yarn", "yarn"),
    Probe.FabricAll("loader", "fabric"),
    Probe.Ornithe("feather"), Probe.Ornithe("raven"),
    Probe.Ornithe("nests"), Probe.Ornithe("sparrow"),
    Probe.Maven("quilt",
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml"),
    Probe.Maven("quilt-mappings",
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/maven-metadata.xml"),
    Probe.Maven("neoforge",
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"),
    Probe.Maven("forge",
        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"),
    Probe.Maven("stationapi",
        "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/maven-metadata.xml"),
    Probe.Maven("osl",
        "https://maven.ornithemc.net/releases/net/ornithemc/osl/maven-metadata.xml"),
    Probe.Maven("foxloader",
        "https://cdn.fox2code.com/maven/com/fox2code/FoxLoader/loader/maven-metadata.xml"),
    Probe.McpIndex,
    Probe.Const("mcp:1.7.10", "stable_12"),
    Probe.Const("mcp:1.12.2", "stable_39"),
    // mojmap: published since 19w36a (1.15 cycle)
    Probe.Computed("mojmap") { p ->
        p["@mc"].orEmpty().filter { v ->
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
    },
)

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

fun main(args: Array<String>) {
    val outDir = File(args[0])
    val p = linkedMapOf<String, List<String>>()
    for (probe in PROBES) runCatching { probe.run(p) }
    outDir.resolve("ProbedVersions.kt").run {
        parentFile.mkdirs()
        writeText(buildString {
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
            appendLine("                val m = runRe.matchEntire(seg.trim())")
            appendLine("                if (m != null) {")
            appendLine("                    val (base, hi, lo) = m.destructured")
            appendLine("                    (hi.toInt() downTo lo.toInt()).map { base + it }")
            appendLine("                } else listOf(seg.trim())")
            appendLine("            }")
            appendLine("}")
        })
    }
}
