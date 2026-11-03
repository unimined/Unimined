package xyz.wagyourtail.unimined.init

/** Version-probe specs shared between the build-time prober
 *  (jvmProbedgen → ProbedVersions.kt) and the runtime resolver
 *  (commonMain, all targets). Transport is injected — the specs
 *  are pure URL + regex parsing, platform-neutral. */
sealed interface VersionProbe {
    fun run(fetch: (String) -> String, p: MutableMap<String, List<String>>)

    /** Plain maven-metadata.xml list, newest-first, optional prefix. */
    class Maven(val key: String, val url: String,
                val prefix: String? = null) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p[key] = mavenVersions(fetch, url, prefix)
        }
    }

    /** piston-meta version_manifest_v2 — all release ids under `@mc`. */
    object Minecraft : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p["@mc"] = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch(PISTON_META)).map { it.groupValues[1] }.toList()
        }
    }

    /** fabric-meta `/v2/versions/<kind>` all-versions, keyed `<key>:<mc>`. */
    class FabricAll(val kind: String, val key: String) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\",[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://meta.fabricmc.net/v2/versions/$kind"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                .forEach { (mc, vs) -> p["$key:$mc"] = vs }
        }
    }

    /** ornithe meta `/v3/versions` section, keyed `<name>:<mc>`. */
    class Ornithe(val name: String) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            val json = fetch("https://meta.ornithemc.net/v3/versions")
            Regex("\"$name\"\\s*:\\s*\\[(.*?)]").find(json)?.let { sec ->
                Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\"[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(sec.groupValues[1])
                    .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                    .forEach { (mc, vs) -> p["$name:$mc"] = vs }
            }
        }
    }

    /** forge files mcp versions.json, keyed `mcp:<mc>` stable_/snapshot_. */
    object McpIndex : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            val json = fetch(
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
                   val f: (Map<String, List<String>>) -> List<String>) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) { p[key] = f(p) }
    }

    /** Constant fallback — always succeeds, index wins when probed. */
    class Const(val key: String, vararg val vs: String) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            if (key !in p) p[key] = vs.toList()
        }
    }

    companion object {
        const val PISTON_META =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** maven-metadata newest-first versions ([prefix] scopes e.g. forge mc). */
        fun mavenVersions(fetch: (String) -> String, url: String,
                          prefix: String?): List<String> {
            val vs = Regex("<version>([^<]+)</version>")
                .findAll(fetch(url)).map { it.groupValues[1] }
                .toList().reversed()
            return prefix?.let { p -> vs.filter { it.startsWith(p) } } ?: vs
        }

        /** fabric-meta per-mc `/v2/versions/<kind>/<mc>` — key version fields. */
        fun fabricMetaVersions(fetch: (String) -> String,
                               kind: String, mc: String, key: String): List<String> =
            Regex("\"$key\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://meta.fabricmc.net/v2/versions/$kind/$mc"))
                .map { it.groupValues[1] }.toList()

        /** Flat per-mc fabric-meta (yarn entries have top-level version). */
        fun fabricMetaFlat(fetch: (String) -> String,
                           kind: String, mc: String): List<String> =
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://meta.fabricmc.net/v2/versions/$kind/$mc"))
                .map { it.groupValues[1] }.toList()
    }
}

/** Bulk probe table — build-time (every axis, baked into Probed.MAP). */
val PROBES: List<VersionProbe> = listOf(
    VersionProbe.Minecraft,
    VersionProbe.Maven("@plugin",
        "https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml"),
    VersionProbe.FabricAll("yarn", "yarn"),
    VersionProbe.FabricAll("loader", "fabric"),
    VersionProbe.Ornithe("feather"), VersionProbe.Ornithe("raven"),
    VersionProbe.Ornithe("nests"), VersionProbe.Ornithe("sparrow"),
    VersionProbe.Maven("quilt",
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml"),
    VersionProbe.Maven("quilt-mappings",
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/maven-metadata.xml"),
    VersionProbe.Maven("neoforge",
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"),
    VersionProbe.Maven("forge",
        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"),
    VersionProbe.Maven("stationapi",
        "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/maven-metadata.xml"),
    VersionProbe.Maven("osl",
        "https://maven.ornithemc.net/releases/net/ornithemc/osl/maven-metadata.xml"),
    VersionProbe.Maven("foxloader",
        "https://cdn.fox2code.com/maven/com/fox2code/FoxLoader/loader/maven-metadata.xml"),
    VersionProbe.McpIndex,
    VersionProbe.Const("mcp:1.7.10", "stable_12"),
    VersionProbe.Const("mcp:1.12.2", "stable_39"),
    // mojmap: published since 19w36a (1.15 cycle)
    VersionProbe.Computed("mojmap") { p ->
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

/** Per-axis runtime table — same endpoint knowledge as the bulk
 *  table, probed lazily for one loader/mc pair. */
val RUNTIME_AXES: Map<String, ((String) -> String, String) -> List<String>> = mapOf(
    "fabric" to { f, mc -> VersionProbe.fabricMetaVersions(f, "loader", mc, "loader") },
    "yarn" to { f, mc -> VersionProbe.fabricMetaFlat(f, "yarn", mc) },
    "forge" to { f, mc -> VersionProbe.mavenVersions(f,
        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml", mc) },
    "neoforge" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", null) },
    "quilt" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml", null) },
    "quilt-mappings" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/maven-metadata.xml", null) },
    "stationapi" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/maven-metadata.xml", null) },
    "osl" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.ornithemc.net/releases/net/ornithemc/osl/maven-metadata.xml", null) },
    "foxloader" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://cdn.fox2code.com/maven/com/fox2code/FoxLoader/loader/maven-metadata.xml", null) },
    "babric" to { f, _ -> emptyList() },
    "bta" to { f, _ -> emptyList() },
    "reindev" to { f, _ -> emptyList() },
)
