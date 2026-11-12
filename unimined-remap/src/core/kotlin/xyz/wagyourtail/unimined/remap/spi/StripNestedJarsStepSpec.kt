package xyz.wagyourtail.unimined.remap.spi

/**
 * Strips jar-in-jar declarations (`jars`, `quilt_loader.jars`) from
 * `*.mod.json` metadata inside *dependency* jars. A dep's nested-jar
 * entries don't survive the dev classpath remap — extraction/repacking
 * is the consumer's problem — so leaving the declaration would make
 * the loader resolve embedded jars that were never remapped (1.x
 * `innerJarRemapper` parity: deps' JiJ keys dropped on the way in).
 *
 * Appended implicitly to dependency remapped-view pipelines
 * (`RemapExtension.dependencyPipelineSteps`); never user-declared and
 * never on the prod-jar pipeline — the environment's own metadata
 * declarations are authored by `mod { }`/nestedJars wiring and must
 * survive.
 *
 * [ModArtifactOnlyStepSpec]: the transform that remaps the
 * environment's Minecraft artifact filters it out — MC jars carry no
 * mod metadata to strip.
 */
class StripNestedJarsStepSpec : ModArtifactOnlyStepSpec {

    override fun toString(): String = "strip-nested-jars()"

}
