package xyz.wagyourtail.unimined.api.environment

/**
 * A configurable section of a source-set Minecraft environment that can be
 * inherited from another environment.
 *
 * Sections are intentionally granular: a loader-specific source set can reuse
 * common mappings and remap settings without also inheriting the common
 * environment's loader. Optional plugins register handlers for the sections
 * they own.
 */
enum class EnvironmentSection {
    /** Minecraft version. */
    VERSION,

    /** Client/server/merged side. */
    SIDE,

    /** Minecraft provider selection. */
    PROVIDER,

    /** Mod-loader identity and version. */
    LOADER,

    /** Mapping declarations. */
    MAPPINGS,

    /** Environment mod dependency buckets. */
    DEPENDENCIES,

    /** Remap surface pipelines. */
    REMAP,

    /** Minecraft transformers and decorators. */
    TRANSFORMS,

    /** Run configuration settings. Reserved for `unimined-runs`. */
    RUNS,

    /** Source/decompiler settings. Reserved for `unimined-source`. */
    SOURCES,

    /** Generated mod metadata/resources. Reserved for `unimined-mod`. */
    MOD_METADATA;

    companion object {
        /**
         * Sections copied by `copyFrom(...)`/`from(...)` when no explicit
         * selection is supplied. Future optional sections deliberately are not
         * included until their owning plugin exists.
         */
        @JvmField
        val COPY_DEFAULT: Set<EnvironmentSection> = setOf(
            VERSION, SIDE, PROVIDER, LOADER, MAPPINGS, REMAP, TRANSFORMS
        )

        /**
         * Sections copied by `combineWith(...)` by default. The loader is
         * excluded so a neutral common environment cannot silently impose a
         * loader on a Fabric/Forge source set; request [LOADER] explicitly if
         * that behavior is intended.
         */
        @JvmField
        val COMBINE_DEFAULT: Set<EnvironmentSection> = COPY_DEFAULT - LOADER
    }
}
