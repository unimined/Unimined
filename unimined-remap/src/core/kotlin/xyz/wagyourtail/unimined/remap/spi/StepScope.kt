package xyz.wagyourtail.unimined.remap.spi

/**
 * Which artifacts a contributed pipeline step applies to.
 *
 * Steps contributed by transform plugins land in every pipeline
 * consuming the environment's step list — the environment's own
 * artifact (the dev Minecraft jar) *and* remapped dependency views
 * (mod classpath buckets). Loader patch steps (binary patching,
 * class injection) only make sense against the environment artifact —
 * applying them to dependency jars would corrupt them.
 */
enum class StepScope {

    /** The environment's own artifact only (the dev jar pipeline). */
    ENVIRONMENT_ARTIFACT,

    /** Every artifact through the pipeline, including remapped
     *  dependency views. The default. */
    ALL_ARTIFACTS

}
