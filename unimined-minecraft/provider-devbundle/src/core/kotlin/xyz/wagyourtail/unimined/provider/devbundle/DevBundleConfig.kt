package xyz.wagyourtail.unimined.provider.devbundle

import kotlinx.serialization.Serializable

/**
 * A parsed paperweight dev bundle — the format `data-version.txt`
 * plus the unified [DevBundleConfig] view.
 *
 * paperweight's per-version config classes flatten into one lenient
 * model here: fields that only exist in some data versions are
 * nullable/absent in others, and the `buildData` block (present on
 * data versions 2–5, inlined at top level on 6+) is preserved so each
 * effective accessor can fall through correctly.
 */
class DevBundle(
    /** Bundle format version (`data-version.txt`) — paperweight 2–8. */
    val dataVersion: Int,
    val config: DevBundleConfig
) {

    /** The vanilla Minecraft version the bundle patches — required
     *  on every data version. */
    val minecraftVersion: String
        get() = config.minecraftVersion ?: throw IllegalStateException(
            "dev bundle config.json has no minecraftVersion"
        )

    /** In-zip path of the mojang-mapped paperclip jar — `buildData.`
     *  on data versions 2–5, top-level on 6+. Required. */
    val mojangMappedPaperclipFile: String
        get() = config.mojangMappedPaperclipFile
            ?: config.buildData?.mojangMappedPaperclipFile
            ?: throw IllegalStateException(
                "dev bundle config.json has no mojangMappedPaperclipFile"
            )

    /** In-zip path of the reobf (mojmap→spigot) mappings, when the
     *  data version still ships one (2–7; dropped by mache-era 8). */
    val reobfMappingsFile: String?
        get() = config.reobfMappingsFile ?: config.buildData?.reobfMappingsFile

    /** In-zip path of the bundle's access-transformer file (data
     *  versions 2–5 only). */
    val accessTransformFile: String?
        get() = config.buildData?.accessTransformFile

    /** In-zip directory holding the fork's source patches — retained
     *  for consumers that apply source-level patches; unimined does
     *  not apply them (the paperclip output is already patched). */
    val patchDir: String?
        get() = config.patchDir

    /** Maven repos the bundle's library coordinates live on —
     *  `buildData.` on 2–5, top-level on 6+. */
    val libraryRepositories: List<String>
        get() = config.libraryRepositories +
                (config.buildData?.libraryRepositories ?: emptyList())

    /** Library coordinates for the environment classpath — the
     *  api coordinates (2–5) plus the compile/runtime dependency
     *  lists; v2 folds its single `libraryDependencies` set in. */
    val libraryCoordinates: List<String>
        get() = listOfNotNull(
            config.apiCoordinates,
            config.mojangApiCoordinates
        ) + (config.buildData?.let {
            it.libraryDependencies + it.compileDependencies + it.runtimeDependencies
        } ?: emptyList())

}

/**
 * Lenient union of the paperweight dev-bundle `config.json` schemas
 * (data versions 2–8). Unknown keys are ignored at decode time.
 */
@Serializable
data class DevBundleConfig(
    val minecraftVersion: String? = null,

    /** Fork api artifact for the compile classpath (data 2–5). */
    val apiCoordinates: String? = null,

    /** Mojang-mapped api artifact for the compile classpath
     *  (data 2–5; nullable from data version 5). */
    val mojangApiCoordinates: String? = null,

    /** In-zip paperclip path — top level on data versions 6+. */
    val mojangMappedPaperclipFile: String? = null,

    /** In-zip reobf-mappings path — top level on data versions 6–7. */
    val reobfMappingsFile: String? = null,

    /** In-zip source-patch directory (all versions). */
    val patchDir: String? = null,

    /** Bundle-declared maven repos — top level on data versions 6+. */
    val libraryRepositories: List<String> = emptyList(),

    /** tiny-remapper args for plugin/mod reobf — surfaced for
     *  consumers; unimined's own remap path does not consume them. */
    val pluginRemapArgs: List<String> = emptyList(),

    /** The `buildData` block — present on data versions 2–5 only. */
    val buildData: BuildData? = null
) {

    @Serializable
    data class BuildData(
        val reobfMappingsFile: String? = null,
        val accessTransformFile: String? = null,
        val mojangMappedPaperclipFile: String? = null,
        val vanillaJarIncludes: List<String> = emptyList(),

        /** v2's flat library set (later versions split
         *  compile/runtime). */
        val libraryDependencies: List<String> = emptyList(),

        /** Entries of the vanilla server jar to keep when generating
         *  the mojmap server — paperweight filters the *server* jar
         *  before remapping; the paperclip output we consume is
         *  already complete, so this is informational only. */
        val vanillaServerLibraries: List<String> = emptyList(),
        val compileDependencies: List<String> = emptyList(),
        val runtimeDependencies: List<String> = emptyList(),
        val libraryRepositories: List<String> = emptyList()
    )

}
