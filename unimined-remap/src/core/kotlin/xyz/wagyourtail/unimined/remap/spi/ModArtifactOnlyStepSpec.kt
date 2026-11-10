package xyz.wagyourtail.unimined.remap.spi

/**
 * Marker for [StepSpec]s that apply only to *mod jars* — they are
 * excluded when the same environment's JAR pipeline runs over the
 * Minecraft environment artifact itself (the dev/modSource artifact
 * transforms).
 *
 * Example: `modArtifacts` rewrites mod-jar metadata (kotlin metadata,
 * mixin configs + refmaps, JarModAgent refmaps) — running it over the
 * MC artifact buys nothing but a full inheritance scan over the whole
 * game jar. By contrast `remap`/`remap-access` are meaningful on both
 * surfaces, so they do not carry this marker.
 *
 * The inverse of [DevArtifactOnlyStepSpec], which marks steps that only
 * make sense on the MC dev artifact.
 */
interface ModArtifactOnlyStepSpec : StepSpec
