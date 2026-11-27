package xyz.wagyourtail.unimined.minecraft

import java.io.File

/**
 * Launch-side consumption of the environment's `mods { "<id>" { } }`
 * groups — the selection rule plus the two loader serializations,
 * shared by every launch surface (`run<Name>`, the `test<Name>` JUnit
 * twin, and IDE-emitted run configs) so the formats can't drift.
 *
 * - `classpathGroups`-capable loaders (fabric-family) take
 *   [classpathGroupsValue] as a system property — fabric-loader #585.
 * - `modGroupsEnv`-capable loaders (forge-family) take
 *   [modClassesEntries] appended to that env var (`MOD_CLASSES`
 *   `id%%dir` pairs — Loom `loom.mods` / NFRT parity).
 */
object ModGroups {

    /**
     * The groups one run loads: [loadedMods] (`RunConfig.loadedMods`)
     * selects a subset by name, an empty set loads every declared
     * group. Mod groups are a dev-launch channel — [prod] runs launch
     * the shipped jar, where dev source-set outputs on the classpath
     * would double the mod's classes (the run wiring itself keeps
     * `sourceSet.output` off prod classpaths for the same reason), so
     * a prod run selects nothing.
     */
    fun selected(
        environment: MinecraftEnvironment,
        loadedMods: Set<String>,
        prod: Boolean
    ): List<ModGroupSpec> =
        if (prod) emptyList()
        else environment.effectiveModGroups()
            .filter { loadedMods.isEmpty() || it.name in loadedMods }

    /**
     * The `classpathGroups` capability value (fabric-loader #585 —
     * `fabric.classPathGroups`): groups separated by a doubled path
     * separator, each group's launch files by a single one. An empty
     * group list serializes to `""`.
     */
    fun classpathGroupsValue(groups: List<ModGroupSpec>): String =
        groups.joinToString(File.pathSeparator + File.pathSeparator) { g ->
            g.launchFiles().joinToString(File.pathSeparator) {
                it.absolutePath
            }
        }

    /**
     * Mod-metadata files that mark a dir as a group's resource root.
     */
    private val MOD_METADATA_FILES = listOf(
        "META-INF/mods.toml",
        "META-INF/neoforge.mods.toml",
        "mcmod.info",
    )

    /**
     * Order a group's launch files so a dir carrying mod metadata
     * leads — forge-family dev runtimes parse `id%%dir` MOD_CLASSES
     * entries into `explodedTargets` pairs where the FIRST path of
     * each group is the resource root scanned for `mods.toml` and
     * the rest are class roots (`FMLCommonLaunchHandler`
     * `processModClassesEnvironmentVariable`; NeoForge-era
     * `neoforge.mods.toml`, FG2-era `mcmod.info` same shape). A
     * metadata-less leader makes the locator skip the whole group
     * silently. Stable: relative order of non-metadata dirs is kept.
     */
    fun metadataFirst(files: List<File>): List<File> =
        files.sortedByDescending { f ->
            MOD_METADATA_FILES.any { File(f, it).isFile }
        }

    /**
     * The `modGroupsEnv` capability entries (forge `MOD_CLASSES`) —
     * one `<id>%%<path>` pair per launch file, `:`s in the group id
     * flattened to `_` (1.x `parent.groups` shape). Files are
     * [metadataFirst]-ordered — the group's resource root must lead.
     */
    fun modClassesEntries(groups: List<ModGroupSpec>): List<String> =
        groups.flatMap { g ->
            metadataFirst(g.launchFiles()).map {
                "${g.name.replace(":", "_")}%%${it.absolutePath}"
            }
        }

    /**
     * Merge [modClassesEntries] output into an existing
     * `modGroupsEnv` value — the loader's own contribution
     * ([existing], e.g. forge's `MOD_CLASSES` from `runs.<side>`)
     * plus the declared mods{} entries — by *label union*, not plain
     * concatenation. Every forge-family dev runtime groups the
     * variable purely by the `%%` prefix: one label becomes one
     * exploded mod target / SecureJar union
     * (`FMLCommonLaunchHandler.processModClassesEnvironmentVariable`,
     * bootstrap-dev `BootstrapDevClasspathFixer`). The loader's own
     * source-set group and a mods{} group for the same mod therefore
     * share a label (the mod id) and must serialize as one union —
     * two labels would split the mod into two modfiles, one of which
     * loses its classes (or double-registers the mod id when both
     * carry metadata).
     *
     * Within each label the paths are [metadataFirst]-ordered: the
     * FIRST path of a group is the resource root scanned for
     * `mods.toml`/`mcmod.info` — a metadata-less leader makes the
     * locator skip the whole group silently. A generated manifest
     * member (`.gradle/unimined/forge/mod-classes`) always trails —
     * SecureJar names the union from the *last* `META-INF` carrier.
     * `null`/`""` inputs drop out; all-empty yields `""`.
     */
    fun mergeModClasses(existing: String?, ours: String): String {
        val entries = mutableListOf<Pair<String, String>>()
        fun ingest(serialized: String?) {
            if (serialized.isNullOrEmpty()) return
            for (entry in serialized.split(File.pathSeparator)) {
                if (entry.isBlank()) continue
                val idx = entry.indexOf("%%")
                // a raw path with no `%%` prefix groups under
                // `defaultmodid` — the runtimes' own fallback
                if (idx == -1) entries += ("defaultmodid" to entry)
                else entries += (entry.substring(0, idx) to
                        entry.substring(idx + 2))
            }
        }
        ingest(existing)
        ingest(ours)
        if (entries.isEmpty()) return ""
        val byLabel = LinkedHashMap<String, MutableList<String>>()
        for ((label, path) in entries) {
            // dedupe within the label — bootstrap-dev collapses exact
            // duplicates, and a repeated dir would double-scan
            val list = byLabel.getOrPut(label) { mutableListOf() }
            if (path !in list) list += path
        }
        return byLabel.flatMap { (label, paths) ->
            orderModClassPaths(paths).map { "$label%%$it" }
        }.joinToString(File.pathSeparator)
    }

    /**
     * Order one label group's MOD_CLASSES paths: metadata-bearing
     * resource roots first (stable), the generated securejar manifest
     * member (`mod-classes` dir) strictly last — SecureJar's
     * `JarMetadata.from` reads the *last* `META-INF` carrier for the
     * union's module name, so nothing may follow it.
     */
    internal fun orderModClassPaths(paths: List<String>): List<String> =
        paths.sortedBy { p ->
            when {
                "mod-classes" in p -> 2
                MOD_METADATA_FILES.any { File(p, it).isFile } -> 0
                else -> 1
            }
        }
}
