package xyz.wagyourtail.unimined.remap.spi

import org.gradle.api.tasks.Input

/**
 * Declares an *emit point*: the artifact state at this position in the
 * pipeline is materialized to [output] as a real jar — mid-pipeline
 * products (e.g. the environment's prod jar — post merge/pre-steps,
 * before the dev namespace remap and post/enhance steps) stay
 * consumable via `envArtifactBindings`, file deps, or tooling even
 * though fused groups no longer write per-step jars.
 *
 * Resolved by `emit` step impls as a [xyz.wagyourtail.unimined.remap.spi.FusibleJarStep]
 * tap: inside a fused group each entry is written to the side output
 * as it flows through, after the preceding steps' transforms — the
 * emitted jar is exactly the mid-state at declaration position, for
 * zero extra traversal.
 *
 * [DevArtifactOnlyStepSpec]: environment emit points materialize the
 * environment's own Minecraft artifact, so prod/remapJar and mod-jar
 * pipelines drop them automatically (their input already *is* the
 * artifact they would emit).
 *
 * [output] is an absolute path string (specs serialize) — the wiring
 * that inserts the spec computes it from the emitted-artifact root
 * (`UniminedCache.emitted`). Consumers resolve the same path lazily:
 * the file exists after a producing transform runs.
 */
class EmitArtifactStepSpec(
    /** Absolute destination for the emitted jar. */
    @get:Input
    val output: String
) : DevArtifactOnlyStepSpec {

    override fun toString(): String = "emit($output)"

}
