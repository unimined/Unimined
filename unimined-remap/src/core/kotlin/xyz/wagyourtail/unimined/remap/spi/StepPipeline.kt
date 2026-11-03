package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.remap.internal.JarSignatureStripper
import java.nio.file.Path

/**
 * Executes an ordered list of [ArtifactStep]s over one artifact.
 * Order is declaration order — the user's order wins over any engine
 * ordering hints.
 */
object StepPipeline {

    /** Apply [steps] in order; returns the final artifact path. */
    fun run(input: Path, steps: List<ArtifactStep>, ctx: StepContext): Path =
        steps.fold(input) { acc, step ->
            step.apply(acc, ctx).also { out ->
                // rewritten class bytes invalidate jar signing metadata —
                // strip it so later steps and consumers can reopen the jar
                JarSignatureStripper.sanitize(out)
            }
        }

}
