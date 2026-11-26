package xyz.wagyourtail.unimined.transform

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import javax.inject.Inject

/**
 * Jar-in-jar packaging knobs for one environment — the `include`
 * bucket's embed behavior. The format itself normally follows the
 * environment's loader at its version (fabric family `fabric`,
 * JarJar-era forge and neoforge `jarjar`).
 */
abstract class NestedJarsSpec @Inject constructor() {

    init {
        recompress.convention(true)
        fmlModTypes.convention(emptyMap())
        obfuscated.convention(emptyMap())
    }

    /**
     * Rewrite embedded jars before packing — entries sorted, stamped
     * DOS epoch, STORED for `fabric` so the outer DEFLATE compresses
     * the whole nested stream once (deterministic + single-pass
     * compression, the 1.x `includeCache` semantic taken further).
     * `false` embeds bytes verbatim. Convention `true`.
     */
    @get:Input
    abstract val recompress: Property<Boolean>

    /** Embed format override (`fabric`|`jarjar`); otherwise a declared
     *  `features { nestedJars("…") }` binding, then the loader's own
     *  format. */
    @get:Optional
    @get:Input
    abstract val format: Property<String>

    /** Default `FMLModType` manifest attribute stamped into every
     *  embedded jar (`MOD`/`LIBRARY`/`GAMELIBRARY`/`LANGPROVIDER`/
     *  `CUSTOM`) — declares each entry's FML layer + scan type without
     *  requiring `mods.toml` inside it. `LIBRARY`/`LANGPROVIDER` land
     *  in the PLUGIN layer (visible to language providers);
     *  `MOD`/`GAMELIBRARY` in GAME. Unset embeds untouched — FML
     *  defaults nested jars to `GAMELIBRARY`. jarjar format only. */
    @get:Optional
    @get:Input
    abstract val fmlModType: Property<String>

    /** Per-entry `FMLModType` overrides — keyed by `group:artifact`
     *  (module deps) or the jar's file name (file deps); wins over
     *  [fmlModType]. */
    @get:Input
    abstract val fmlModTypes: MapProperty<String, String>

    /** Per-entry `isObfuscated` metadata overrides — same keying as
     *  [fmlModTypes]. Unset entries detect from the nested jar's
     *  `Obfuscated-By` manifest attribute. */
    @get:Input
    abstract val obfuscated: MapProperty<String, Boolean>

}
