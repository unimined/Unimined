package xyz.wagyourtail.unimined.api.spec

/**
 * What an `unimined-minecraft` provider is asked to produce: a game
 * environment described by version, [Side], an optional [LoaderSpec],
 * and the [product] the env targets (`native:minecraft` for vanilla).
 *
 * Providers declare `supports(ProductSpec)` against this contract; the
 * provider SPI host negotiates per environment.
 */
interface ProductSpec {

    /** Stable environment identity, normally the source-set name. */
    val environmentId: String
        get() = "main"

    /** Source set this environment is wired to, or `null` for standalone use. */
    val sourceSetName: String?
        get() = null

    val version: ProductVersionSpec

    val side: Side

    /** `null` for a bare (unmodded / no-loader) environment. */
    val loader: LoaderSpec?

    /** Product this environment produces, scoped by game family —
     *  `minecraft:native` (vanilla, the default), `minecraft:reindev`,
     *  `minecraft:bta`, `custom:<name>` for user-supplied dists.
     *  Providers key their product tables on this; loader↔product
     *  compatibility is validated against the loader descriptor's
     *  `supportedProducts` at env binding. */
    val product: String
        get() = PRODUCT_NATIVE

    /** Typed library-version substitutions applied to version-JSON
     *  `libraries` before dependency creation: `(coordPrefix, version)`
     *  pairs — a library whose `group:name` starts with the prefix gets
     *  its version component replaced (1.x `replaceLibraryVersion`).
     *  Providers apply these for the environment; transforms/envs
     *  register rules such as the LWJGL2 patched-build swap. */
    val librarySubstitutions: List<Pair<String, String>>
        get() = emptyList()

    /** Rich library rules (1.x `replaceLibraryVersion`) — evaluated by
     *  [applyLibraryRules] after [DEFAULT_LIBRARY_RULES] and before
     *  [librarySubstitutions], so user rules override loader
     *  conventions. */
    val libraryRules: List<LibraryRule>
        get() = emptyList()

    /** Whole-coord rewrite filters (1.x `libraryFilter(String)`) —
     *  each maps a coordinate to its replacement or `null` to drop;
     *  last-declared runs first, ahead of every rule list. */
    val libraryCoordFilters: List<(String) -> String?>
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

    /** Coord prefixes (`group:name` or longer) of version-JSON libraries
     *  that are themselves obfuscated Mojang artifacts — they must ride
     *  the raw→dev remap view alongside the MC jar instead of landing on
     *  the classpath in production namespace (issue #27: the Realms
     *  jar). The provider always flags `com.mojang:realms`; loaders and
     *  users contribute extras here. */
    val obfuscatedLibraries: List<String>
        get() = emptyList()

    /** User-declared game-artifact source (env `legacydist {}`) —
     *  non-null selects the declared-source provider instead of any
     *  manifest-backed provider; works for non-MC-version jars
     *  (BTA/ReIndev, pre-1.8 bukkit, offline/air-gapped). */
    val declaredSource: DeclaredSourceSpec?
        get() = null

    companion object {

        /** The vanilla product id — the default [product]. */
        const val PRODUCT_NATIVE = "minecraft:native"

        /** Product-id scope prefix for user-declared dists
         *  (`custom:<name>`) — outside any game family. */
        const val PRODUCT_CUSTOM_SCOPE = "custom"

    }

}
