package xyz.wagyourtail.unimined.transform.loader

import xyz.wagyourtail.unimined.api.spec.ProductSpec

/**
 * A modloader product, as data — replaces 1.x's product-subclass
 * hierarchy (`FabricLike`/`Quilt`/`Babric`; `ForgeLike`/`NeoForged`/
 * `Cleanroom`). Variants that differ only in coordinates, repo URLs,
 * namespace names, or metadata keys are rows in [LoaderRegistry], not
 * classes.
 *
 * Loader *versions* never drive logic here — version-era structure
 * (forge's jar-patches/binpatches/userdev packaging) is a separate axis,
 * probed once from the resolved artifact by the family transform.
 */
data class LoaderDescriptor(

    /** Selector id — `loader("fabric", "0.15.7")`. */
    val id: String,

    /** The transform family that claims this product —
     *  `fabric`/`forge`/`jarmod`/`bukkit`/`misc`. Family wiring matches
     *  on this, never on [id]. */
    val family: String,

    /** Game-family-scoped products this loader supports
     *  (`minecraft:native`, `minecraft:reindev`, `minecraft:bta`,
     *  or `custom` for user-supplied dists). `null` = native vanilla
     *  only — most loaders never declare it. `*` opts into any
     *  product (jarmod); a scope prefix (`minecraft`, `custom`)
     *  accepts any product in that family. Env binding validates
     *  the env's product against this set. */
    val supportedProducts: Set<String>? = null,

    /** Repositories this product's artifacts resolve from — products
     *  can need several (cleanroom adds arcseekers + forge mavens) and
     *  of mixed shape (ivy pattern-layout feeds like liteloader's
     *  legacy redist). */
    val repos: List<LoaderRepo>,

    /** Dependency coordinates for the loader artifact for
     *  `(mcVersion, loaderVersion)` — e.g.
     *  `{ mc, v -> "net.minecraftforge:forge:$mc-$v" }`. Base GAV only:
     *  classifier/extension (`:universal`, `@zip`, side classifiers,
     *  `:userdev`) is the era pipeline's call, not product data. */
    val loaderCoords: (String, String) -> String,

    /** Production-side mapping namespace the loader runs on
     *  (`intermediary`/`searge`/`mojmap`/`legacyIntermediary`/…). */
    val prodNamespace: String,

    /** Production-side mapping artifact coordinates for `(mcVersion)` —
     *  the intermediary/calamus jar the family transform resolves for
     *  the `official → prodNs` hop (fabric-family `*:intermediary:*:v2`
     *  shape). `null` for products whose production mappings arrive
     *  another way (forge userdev / mcp-config). */
    val intermediaryCoords: ((String) -> String)? = null,

    /** Named upstream-mapping channel the environment contributes
     *  through its own `mappings {}` channel API instead of a bare
     *  coord — for products whose intermediary artifact is gen- or
     *  side-shaped and needs the channel's namespace relabels
     *  (e.g. `"calamus"` → `MappingsSpecImpl.calamus()`). Supersedes
     *  [intermediaryCoords] when present. */
    val upstreamMappingChannel: String? = null,

    /** Mod-metadata conventions (metadata file name, where AT/CT files
     *  and injected-interface/nested-jar declarations live). */
    val metadata: LoaderMetadataSpec = LoaderMetadataSpec(),

    /** Minimum Java the product's runtime requires — raises the
     *  environment's java floor (the version-json `javaVersion`) for
     *  the compile toolchain, run launcher, and tool execs.
     *  `(mcVersion, loaderVersion) -> floor`; cleanroom → 25 (its
     *  artifacts carry jvm-version=25 metadata), ornithe → 17,
     *  neoforge → 21 on the 1.20.1-forge artifact (JDK25-class deps
     *  crash its ASM) and 25 after. `null` leaves the version-json
     *  requirement. */
    val javaVersion: ((String, String) -> Int?)? = null,

    /** Static product capabilities, surfaced through
     *  `EnvironmentCapabilities.loaderCapabilities`
     *  (`atTool`/`modFormat`/`unionRelauncher`/…). */
    val capabilities: Map<String, String> = emptyMap(),

    /** Gradle plugin id of the family transform this product needs —
     *  `xyz.wagyourtail.unimined.transform.forge`. The frontend applies
     *  it lazily once an environment declares this loader, instead of
     *  stacking every transform onto every project. `null` for products
     *  with no transform plugin (handled by the core stack). */
    val transformPlugin: String? = null,

    /** Variant the family wiring dispatches on when a product needs
     *  its own env-wiring path — e.g. `"liteloader"` routes to
     *  `LiteLoaderWiring` inside the jarmod family instead of the
     *  jarMod merge/agent path. `null` = the family's default wiring.
     *  (Function refs can't live here — env types would invert the
     *  transform→minecraft dependency.) */
    val wiringVariant: String? = null,

    ) {

    /**
     * Whether this loader supports [product] — the env-binding check
     * 2.0 runs once per environment. Match order:
     * `*` (any product) → exact product id → scope prefix (`custom`
     * accepts `custom:<name>`, `minecraft` accepts `minecraft:*`).
     * A `null` [supportedProducts] accepts only the native product.
     */
    fun supportsProduct(product: String): Boolean =
        supportedProducts
            ?.any {
                it == "*" || it == product ||
                        product.startsWith("$it:")
            }
            ?: (product == ProductSpec.PRODUCT_NATIVE)

}

/** Loader-declared access-file (AW/CT/AT) discovery mechanisms. */
enum class AccessDiscovery {

    /** `accessWidener` key in the mod metadata file (fabric.mod.json). */
    METADATA_KEY,

    /** `classTweakers` key in the mod metadata file (fmj v2). */
    METADATA_CLASS_TWEAKERS,

    /** `META-INF/accesstransformer.cfg` convention (FML). */
    META_INF_ACCESSTRANSFORMER,

    /** `FMLAT` jar-manifest attribute (old FML custom AT paths). */
    FMLAT_MANIFEST,

}

/** Where a loader's metadata file and its unimined-relevant keys live. */
data class LoaderMetadataSpec(

    /** Mod metadata file inside the mod jar (`fabric.mod.json`,
     *  `quilt.mod.json`, `META-INF/mods.toml`, `mcmod.info`, …). */
    val fileName: String? = null,

    /** How loader-declared AW/CT/AT files are discovered in mod jars. */
    val accessDiscovery: Set<AccessDiscovery> = emptySet(),

    /** Metadata key path to compile-visible interface injections
     *  (`custom.loom:injected_interfaces` /
     *  `custom.quilt_loom.injected_interfaces`). */
    val injectedInterfacesKey: String? = null,

    /** Metadata key path listing nested jars
     *  (`jars` / `quilt_loader.jars`). */
    val nestedJarsKey: String? = null,

    )
