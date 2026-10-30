package xyz.wagyourtail.unimined.remap.spi

import java.nio.file.Path

/**
 * A composable unit of work in a surface's pipeline: one artifact in, one
 * artifact out. Steps are created per-run by [RemapStepType.create] and
 * applied in declaration order.
 */
fun interface ArtifactStep {

    /**
     * Transform [input] (a jar or source jar on disk) and return the path
     * of the produced artifact. Implementations may write to
     * [StepContext.workDir] and must not mutate [input] in place.
     */
    fun apply(input: Path, ctx: StepContext): Path

}
