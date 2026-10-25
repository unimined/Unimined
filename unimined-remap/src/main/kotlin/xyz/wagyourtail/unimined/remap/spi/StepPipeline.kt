package xyz.wagyourtail.unimined.remap.spi

import java.nio.file.Path

/**
 * Executes an ordered list of [ArtifactStep]s over one artifact.
 * Order is declaration order — the user's order wins over any engine
 * ordering hints.
 */
object StepPipeline {

    /** Apply [steps] in order; returns the final artifact path. */
    fun run(input: Path, steps: List<ArtifactStep>, ctx: StepContext): Path =
        steps.fold(input) { acc, step -> step.apply(acc, ctx) }

}
