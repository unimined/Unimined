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

}
