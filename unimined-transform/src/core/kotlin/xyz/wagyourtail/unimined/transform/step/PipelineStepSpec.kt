package xyz.wagyourtail.unimined.transform.step

import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.transform.processor.JarProcessor

/**
 * A declared pipeline step's contribution to the env's artifact
 * pipeline — a [StepSpec] riding an existing [xyz.wagyourtail.unimined.remap.spi.RemapStepType]
 * (jar-in/jar-out steps are the *remap* step family; pipeline
 * `step("x")` declarations compose them, they don't re-implement them).
 */
data class RemapStepContribution(
    val surface: RemapSurface,
    val spec: StepSpec,
    /** `contributePreStep` — runs before the declared pipeline, on
     *  the original-namespace input. */
    val pre: Boolean = false,
    val scope: StepScope = StepScope.ALL_ARTIFACTS
)

/**
 * Contract for a declared pipeline step's configured spec — how the
 * step contributes work to the environment. Implemented by the spec
 * class a [StepType]'s DSL verb configures (e.g. jarmod's merge step
 * spec yields [JarModMergeProcessor]-shaped processors).
 *
 * Declared steps ride the existing execution channels:
 * [jarProcessors] run inside the `jar-processors` artifact step in
 * realize() order; [stepSpecs] contribute onto the env's declared
 * artifact pipeline via `RemapEnvironmentSpec.contributeStep`.
 */
interface PipelineStepSpec {

    /** Jar-surface work this step contributes, in step order. */
    fun jarProcessors(): List<JarProcessor> = emptyList()

    /** Artifact-pipeline step contributions (binpatches, merges…). */
    fun stepSpecs(): List<RemapStepContribution> = emptyList()

}
