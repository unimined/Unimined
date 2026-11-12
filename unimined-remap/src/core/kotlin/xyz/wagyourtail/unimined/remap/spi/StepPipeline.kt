package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.remap.internal.FusedJarRun
import xyz.wagyourtail.unimined.remap.internal.JarSignatureStripper
import java.nio.file.Path

/**
 * Executes an ordered list of [ArtifactStep]s over one artifact.
 * Order is declaration order — the user's order wins over any engine
 * ordering hints. Consecutive [FusibleJarStep]s share a single jar
 * traversal ([FusedJarRun]) — one open/write per group rather than
 * per step — and signature metadata is stripped per produced
 * artifact (per group), not per step.
 */
object StepPipeline {

    /** Apply [steps] in order; returns the final artifact path. */
    fun run(input: Path, steps: List<ArtifactStep>, ctx: StepContext): Path {
        var acc = input
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            val out = if (step is FusibleJarStep) {
                // gather the consecutive fusible run — declaration
                // order is preserved inside the group's visitor chain
                var j = i
                val group = mutableListOf<FusibleJarStep>()
                while (j < steps.size && steps[j] is FusibleJarStep) {
                    group += steps[j] as FusibleJarStep
                    j++
                }
                i = j
                FusedJarRun.run(acc, group, ctx, intermediate = j < steps.size)
            } else {
                i++
                step.apply(acc, ctx)
            }
            // rewritten class bytes invalidate jar signing metadata —
            // strip it so later steps and consumers can reopen the jar
            JarSignatureStripper.sanitize(out)
            acc = out
        }
        return acc
    }

}
