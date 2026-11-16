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
     * The `modGroupsEnv` capability entries (forge `MOD_CLASSES`) —
     * one `<id>%%<path>` pair per launch file, `:`s in the group id
     * flattened to `_` (1.x `parent.groups` shape).
     */
    fun modClassesEntries(groups: List<ModGroupSpec>): List<String> =
        groups.flatMap { g ->
            g.launchFiles().map {
                "${g.name.replace(":", "_")}%%${it.absolutePath}"
            }
        }

    /**
     * Merge [modClassesEntries] output into an existing
     * `modGroupsEnv` value — the loader's own contribution
     * ([existing], e.g. forge's `MOD_CLASSES` from `runs.<side>`)
     * stays first, ours append path-separated. `null`/`""` inputs
     * drop out; all-empty yields `""`.
     */
    fun mergeModClasses(existing: String?, ours: String): String =
        listOfNotNull(existing, ours.takeIf(String::isNotEmpty))
            .joinToString(File.pathSeparator)
}
