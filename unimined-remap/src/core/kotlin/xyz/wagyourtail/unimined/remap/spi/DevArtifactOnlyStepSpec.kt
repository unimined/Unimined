package xyz.wagyourtail.unimined.remap.spi

/**
 * Marker for [StepSpec]s that apply only to the environment's *Minecraft
 * dev artifact* — they are excluded when the same environment's JAR
 * pipeline runs over a production jar (`remapJar`) or a mod dependency.
 *
 * Example: `apply-class-tweaker` widens Minecraft classes on
 * the dev artifact; running it over a mod jar would be meaningless —
 * the mod's classes aren't the widener's targets. By contrast
 * `remap-access` (rebase embedded AW/AT resources) *is* meaningful on a
 * production jar, so it does not carry this marker.
 */
interface DevArtifactOnlyStepSpec : StepSpec
