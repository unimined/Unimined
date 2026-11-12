package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.remap.internal.FusedJarRun
import java.nio.file.Path

/**
 * One jar entry flowing through a fused pipeline group — [name] is the
 * zip path, [bytes] the contents as produced by the previous step's
 * visitor (or the input jar for the first). A step transforms an entry
 * by returning a new [FusedJarEntry]; returning `null` drops it.
 */
class FusedJarEntry(
    val name: String,
    val bytes: ByteArray,
    /** Source zip-entry timestamp — tap/emit steps preserve it so
     *  emitted artifacts stay deterministic. `0` for entries a step
     *  constructs without a timestamp. */
    val time: Long = 0
)

/**
 * An [ArtifactStep] whose work decomposes into a jar-level preparation
 * plus a per-entry transformation — steps of this shape can share one
 * jar traversal when they appear consecutively in a pipeline
 * ([StepPipeline] fuses the run): one open, one ordered pass where each
 * entry flows through every visitor in declaration order, one write.
 * The step still runs standalone through [apply] — a fused group of
 * one — so no separate whole-jar implementation is needed.
 *
 * Fusion never reorders steps: only *consecutive* [FusibleJarStep]s
 * group, and a non-fusible step (or a step returning `null` from
 * [visitEntry]-level boundaries) breaks the run at its position.
 */
fun interface FusibleJarStep : ArtifactStep {

    /**
     * Standalone execution — a fused group of one. Steps get whole-jar
     * behavior for free; [StepPipeline] only calls this on isolated
     * fusible steps (or a caller applying a step directly).
     */
    override fun apply(input: Path, ctx: StepContext): Path =
        FusedJarRun.run(input, listOf(this), ctx)

    /**
     * Jar-level preparation over the shared group input — read
     * manifests, resolve trees, run analysis. Called once per fused
     * group before any [visitEntry]; state captured here is what the
     * visitor uses. Default: no preparation.
     */
    fun prepareFused(input: Path, ctx: StepContext) {}

    /**
     * Transform one entry. [entry] carries the bytes the previous
     * visitor emitted (input bytes for the first visitor); return the
     * replacement (possibly renamed via [FusedJarEntry.name]) or `null`
     * to drop the entry from the group output.
     */
    fun visitEntry(entry: FusedJarEntry, ctx: StepContext): FusedJarEntry?

    /**
     * Directory-entry hook — [FusedJarRun] calls this once per zip
     * directory entry (name ending in `/`, empty [FusedJarEntry.bytes]);
     * returning `false` drops it from the group output. Default `true`
     * (keep) — most steps don't care about directory entries. Tap/emit
     * steps override this to write them to their side output.
     */
    fun visitDirectory(entry: FusedJarEntry, ctx: StepContext): Boolean = true

    /**
     * Called once after the last entry of the group (successful run
     * only) — the point to flush buffered state or emit per-step
     * metrics, since per-entry logging inside a fused group is
     * meaningless. Default: nothing.
     */
    fun endFused(ctx: StepContext) {}

}
