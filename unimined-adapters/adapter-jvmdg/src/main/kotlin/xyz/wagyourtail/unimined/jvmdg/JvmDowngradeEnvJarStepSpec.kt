package xyz.wagyourtail.unimined.jvmdg

import xyz.wagyourtail.jvmdg.gradle.flags.ShadeFlags
import xyz.wagyourtail.unimined.jvmdg.internal.JvmdgEnvWiring
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.StepScope

/**
 * The environment-pipeline form of [JvmDowngradeStepSpec], contributed
 * by [JvmdgEnvWiring] when a `jvmdowngrader`-enabled env compiles above
 * its emit [JvmDgEnvSpec.target] — the env's production jar then
 * carries era-readable bytecode instead of raw `javac` output, giving
 * `runProd` and published artifacts the same contract dev runs get
 * from downgraded class dirs.
 *
 * [ModArtifactOnlyStepSpec] keeps the step off the Minecraft
 * environment artifact itself (already era bytecode — a rewrite would
 * buy nothing but risk), and the contribution's
 * [StepScope.ENVIRONMENT_ARTIFACT] scope keeps it off remapped
 * dependency views — it lands only in the env's own prod jar.
 *
 * Implements [ShadeFlags] so the env spec's whole engine-flag surface
 * (`downgradeTo`, `apiJar`, `ignoreWarningsIn`, `multiReleaseVersions`,
 * …) conventions onto it in one `convention(spec)` call — the step
 * type reads them via [xyz.wagyourtail.jvmdg.gradle.flags.toFlags].
 */
internal abstract class JvmDowngradeEnvJarStepSpec :
    JvmDowngradeStepSpec(), ModArtifactOnlyStepSpec, ShadeFlags
