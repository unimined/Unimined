package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import java.nio.file.Path

/**
 * Everything a step declared it needs, materialized at execution time.
 */
interface StepContext {

    /** The remap request this run serves — source/target namespace and the
     *  resolved classpath entries. */
    val spec: RemapSpec

    /**
     * Resolved mapping data for this run — `null` when no step in the
     * pipeline declared [RemapStepType.needsMappingTree]. Exposed as
     * typed views ([MappingData.view]) so non-remap steps (unpick
     * resolving against the target namespace, nests, AT/AW) share the
     * same resolved tree instead of re-resolving.
     */
    val mappings: MappingData?

    /** Classpath entries — present only when a step declared
     *  [RemapStepType.needsClasspath]. */
    val classpath: List<Path>

    /**
     * The merged AW/AT/CT access rules for the remap batch — the
     * remap-analysis overlay `remap` engines feed to their analysis pass
     * so widened members participate in member propagation. `null` when
     * the host contributed no model or no step declared
     * [RemapStepType.needsAccessModel]. The overlay is strictly additive:
     * its absence must never fail a step.
     */
    val accessModel: AccessModel?
        get() = null

    /**
     * Mutable run state shared by all steps in this pipeline — the
     * artifact's live namespace and the environment's side. Remap
     * steps update `state.currentNamespace` after producing output.
     */
    val state: PipelineState

    /** Scratch directory for step intermediates. */
    val workDir: Path

    val logger: CommonsLogger

    /** Env-scoped structured logger (`[Unimined/<env>/…]`) — steps should
     *  prefer `log.subLogger("<type>:<step>")` over raw [logger] so output
     *  carries the environment it belongs to. */
    val log: CommonsLogger
        get() = logger

}
