package xyz.wagyourtail.unimined.api.spec

/**
 * What an `unimined-minecraft` provider is asked to produce: a Minecraft
 * environment described by version, [Side], and optionally a [LoaderSpec].
 *
 * Providers declare `supports(MinecraftSpec)` against this contract; the
 * provider SPI host negotiates per environment.
 */
interface MinecraftSpec {

    /** Stable environment identity, normally the source-set name. */
    val environmentId: String
        get() = "main"

    /** Source set this environment is wired to, or `null` for standalone use. */
    val sourceSetName: String?
        get() = null

    val version: MinecraftVersionSpec

    val side: Side

    /** `null` for a bare (unmodded / no-loader) environment. */
    val loader: LoaderSpec?

    /** Typed library-version substitutions applied to version-JSON
     *  `libraries` before dependency creation: `(coordPrefix, version)`
     *  pairs — a library whose `group:name` starts with the prefix gets
     *  its version component replaced (1.x `replaceLibraryVersion`).
     *  Providers apply these for the environment; transforms/envs
     *  register rules such as the LWJGL2 patched-build swap. */
    val librarySubstitutions: List<Pair<String, String>>
        get() = emptyList()

    /** Additional ordered version-manifest locations searched after
     *  Mojang's official manifest (first match wins — official entries
     *  always take precedence; alternates fill gaps). Any manifest
     *  serving the Mojang schema works, e.g. skyrising's mc-versions
     *  `version_manifest.json` which restores removed alpha/beta/
     *  classic, april-fools and experimental entries. */
    val manifestUrls: List<String>
        get() = emptyList()

    /** URLs of library-overlay documents merged into the version JSON's
     *  `libraries` before dependency creation. Each serves a JSON array
     *  of `{name, url?, maxGameVersion?}` entries — `name` is a maven
     *  coordinate; entries whose `maxGameVersion` is below the spec's
     *  version are skipped (ornithe-meta's per-generation overlay
     *  backporting slf4j/log4j + modernized deps onto pre-1.7 versions). */
    val libraryOverlays: List<String>
        get() = emptyList()

}
