package xyz.wagyourtail.unimined.transform.step

import xyz.wagyourtail.unimined.transform.processor.JarProcessor

/**
 * Contract for a declared pipeline step's configured spec — how the
 * step contributes work to the environment. Implemented by the spec
 * class a [StepType]'s DSL verb configures (e.g. jarmod's merge step
 * spec yields [JarModMergeProcessor]-shaped processors).
 *
 * Declared steps ride the existing execution channels — the
 * `jar-processors` artifact step runs their [jarProcessors] in
 * realize() order; decorators/run mutators are separate contribution
 * channels (features, not steps).
 */
interface PipelineStepSpec {

    /** Jar-surface work this step contributes, in step order. */
    fun jarProcessors(): List<JarProcessor> = emptyList()

}
