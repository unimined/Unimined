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

    /** Union of several maven-metadata lists — first URL orders newest. */
    class MavenMulti(val key: String, val urls: List<String>) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p[key] = urls.flatMap { mavenVersions(fetch, it, null) }
                .distinct()
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

    /** fabric-meta-style `/v2/versions/<kind>` all-versions,
     *  keyed `<key>:<mc>` (fabric + legacyfabric hosts). */
    class FabricAll(val kind: String, val key: String,
                    val host: String = "https://meta.fabricmc.net") : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\",[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("$host/v2/versions/$kind"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                .forEach { (mc, vs) -> p["$key:$mc"] = vs }
        }
    }

    /** Flat json array of objects — `"version"` fields → [key]. */
    class FlatJson(val key: String, val url: String,
                   val field: String = "version") : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p[key] = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch(url)).map { it.groupValues[1] }.toList()
        }
    }

    /** ornithe meta `/v3/versions/<name>` — flat entries keyed
     *  `<name>:<mc>` (raven/sparrow/nests/feather). */
    class Ornithe(val name: String) : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            Regex("\"gameVersion\"\\s*:\\s*\"([^\"]+)\"[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://meta.ornithemc.net/v3/versions/$name"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
                .forEach { (mc, vs) -> p["$name:$mc"] = vs }
        }
    }

    /** BTA downloads `<channel>/versions.json` → `versions` array,
     *  `v` stripped (the DSL version is bare `1.7.7.0_02`). */
    class Bta(val key: String, val channel: String,
              val side: String = "client") : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p[key] = Regex("\"([^\"]+)\"")
                .findAll(fetch(
                    "https://downloads.betterthanadventure.net/bta-$side/$channel/versions.json"))
                .map { it.groupValues[1] }
                .filter { it != "versions" }
                .map { it.removePrefix("v") }.toList().reversed()
        }
    }

    /** ReIndev — fox2code cdn index, `reindev-<v>-client.jar` hrefs. */
    object Reindev : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p["reindev"] = Regex("reindev-([^\"]+?)-client\\.jar")
                .findAll(fetch("https://cdn.fox2code.com/files/"))
                .map { it.groupValues[1] }.toList().reversed()
        }
    }

    /** RetroMCP — mcphackers versionsV3, `id` = supported mc. */
    object RetroMcp : VersionProbe {
        override fun run(fetch: (String) -> String,
                         p: MutableMap<String, List<String>>) {
            p["retromcp"] = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://mcphackers.org/versionsV3/versions.json"))
                .map { it.groupValues[1] }.toList()
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
        const val FORGE_MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
        const val NEOFORGE_MAVEN =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        private const val PLUGIN_GA =
            "xyz/wagyourtail/unimined/xyz.wagyourtail.unimined.gradle.plugin"
        const val PLUGIN_SNAPSHOTS =
            "https://maven.wagyourtail.xyz/snapshots/$PLUGIN_GA/maven-metadata.xml"
        const val PLUGIN_RELEASES =
            "https://maven.wagyourtail.xyz/releases/$PLUGIN_GA/maven-metadata.xml"

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
        fun fabricMetaFlat(fetch: (String) -> String, kind: String,
                           mc: String,
                           host: String = "https://meta.fabricmc.net"
        ): List<String> =
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("$host/v2/versions/$kind/$mc"))
                .map { it.groupValues[1] }.toList()

        /** String elements of `"<field>": ["a", "b", ...]` array. */
        fun jsonStringArray(fetch: (String) -> String, url: String,
                            field: String): List<String> =
            Regex("\"$field\"\\s*:\\s*\\[([^]]*)]").find(fetch(url))
                ?.let { m -> Regex("\"([^\"]+)\"")
                    .findAll(m.groupValues[1])
                    .map { it.groupValues[1] }.toList() } ?: emptyList()

        /** All `<field>` values from a flat json array/object. */
        fun flatJson(fetch: (String) -> String, url: String,
                     field: String = "version"): List<String> =
            Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch(url)).map { it.groupValues[1] }.toList()

        /** ornithe per-mc — global section filtered by gameVersion. */
        fun ornitheVersions(fetch: (String) -> String,
                            name: String, mc: String): List<String> =
            Regex("\"gameVersion\"\\s*:\\s*\"$mc\"[^}]{0,200}?\"version\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(fetch("https://meta.ornithemc.net/v3/versions/$name"))
                .map { it.groupValues[1] }.toList()
    }
}

/** Bulk probe table — build-time (every axis, baked into Probed.MAP). */
val PROBES: List<VersionProbe> = listOf(
    VersionProbe.Minecraft,
    VersionProbe.MavenMulti("@plugin",
        listOf(VersionProbe.PLUGIN_SNAPSHOTS, VersionProbe.PLUGIN_RELEASES)),
    VersionProbe.FabricAll("yarn", "yarn"),
    VersionProbe.FabricAll("loader", "fabric"),
    VersionProbe.Ornithe("feather"), VersionProbe.Ornithe("raven"),
    VersionProbe.Ornithe("nests"), VersionProbe.Ornithe("sparrow"),
    VersionProbe.FlatJson("intermediary",
        "https://meta.fabricmc.net/v2/versions/intermediary"),
    VersionProbe.FlatJson("legacyfabric",
        "https://meta.legacyfabric.net/v2/versions/loader"),
    VersionProbe.FlatJson("legacyintermediary",
        "https://meta.legacyfabric.net/v2/versions/intermediary"),
    VersionProbe.FabricAll("yarn", "legacyyarn",
        "https://meta.legacyfabric.net"),
    VersionProbe.Bta("bta", "release"),
    VersionProbe.Bta("bta-prerelease", "prerelease"),
    VersionProbe.Bta("bta-server", "release", "server"),
    VersionProbe.Reindev,
    VersionProbe.RetroMcp,
    VersionProbe.Maven("barn",
        "https://maven.glass-launcher.net/babric/babric/barn/maven-metadata.xml"),
    VersionProbe.Maven("biny",
        "https://maven.glass-launcher.net/releases/net/glasslauncher/biny/maven-metadata.xml"),
    VersionProbe.Maven("nostalgia",
        "https://maven.wispforest.io/releases/me/alphamode/nostalgia/maven-metadata.xml"),
    VersionProbe.Maven("hashed",
        "https://maven.quiltmc.org/repository/release/org/quiltmc/hashed/maven-metadata.xml"),
    VersionProbe.FlatJson("calamus",
        "https://meta.ornithemc.net/v3/versions/gen2/game/intermediary"),
    VersionProbe.FlatJson("calamus-gen1",
        "https://meta.ornithemc.net/v3/versions/gen1/game/intermediary"),
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
    "forge" to { f, mc -> VersionProbe.mavenVersions(f, VersionProbe.FORGE_MAVEN, mc) },
    "neoforge" to { f, _ -> VersionProbe.mavenVersions(f, VersionProbe.NEOFORGE_MAVEN, null) },
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
    "barn" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.glass-launcher.net/babric/babric/barn/maven-metadata.xml", null) },
    "biny" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.glass-launcher.net/releases/net/glasslauncher/biny/maven-metadata.xml", null) },
    "nostalgia" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.wispforest.io/releases/me/alphamode/nostalgia/maven-metadata.xml", null) },
    "hashed" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.quiltmc.org/repository/release/org/quiltmc/hashed/maven-metadata.xml", null) },
    "calamus" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.ornithemc.net/releases/net/ornithemc/calamus/maven-metadata.xml", null) },
    "legacyfabric" to { f, _ -> VersionProbe.flatJson(f,
        "https://meta.legacyfabric.net/v2/versions/loader") },
    "legacyintermediary" to { f, _ -> VersionProbe.flatJson(f,
        "https://meta.legacyfabric.net/v2/versions/intermediary") },
    "intermediary" to { f, _ -> VersionProbe.flatJson(f,
        "https://meta.fabricmc.net/v2/versions/intermediary") },
    "legacyyarn" to { f, mc -> VersionProbe.fabricMetaFlat(f,
        "yarn", mc, "https://meta.legacyfabric.net") },
    "bta" to { f, _ -> VersionProbe.jsonStringArray(f,
        "https://downloads.betterthanadventure.net/bta-client/release/versions.json",
        "versions").map { it.removePrefix("v") }.reversed() },
    "reindev" to { f, _ -> Regex("reindev-([^\"]+?)-client\\.jar")
        .findAll(f("https://cdn.fox2code.com/files/"))
        .map { it.groupValues[1] }.toList().reversed() },
    "retromcp" to { f, _ -> VersionProbe.flatJson(f,
        "https://mcphackers.org/versionsV3/versions.json", "id") },
    "calamus" to { f, _ -> VersionProbe.flatJson(f,
        "https://meta.ornithemc.net/v3/versions/gen2/game/intermediary") },
    "barn" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.glass-launcher.net/babric/babric/barn/maven-metadata.xml", null) },
    "biny" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.glass-launcher.net/releases/net/glasslauncher/biny/maven-metadata.xml", null) },
    "nostalgia" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.wispforest.io/releases/me/alphamode/nostalgia/maven-metadata.xml", null) },
    "hashed" to { f, _ -> VersionProbe.mavenVersions(f,
        "https://maven.quiltmc.org/repository/release/org/quiltmc/hashed/maven-metadata.xml", null) },
    "feather" to { f, mc -> VersionProbe.ornitheVersions(f, "feather", mc) },
    "raven" to { f, mc -> VersionProbe.ornitheVersions(f, "raven", mc) },
    "nests" to { f, mc -> VersionProbe.ornitheVersions(f, "nests", mc) },
    "sparrow" to { f, mc -> VersionProbe.ornitheVersions(f, "sparrow", mc) },
)
