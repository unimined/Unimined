package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.api.spec.Side

/**
 * Mutable run state shared by every step of one [StepPipeline] run —
 * the 1.x model where the environment's side and the artifact's real
 * namespace travel with the artifact through the pipeline.
 *
 * Steps read [currentNamespace] to know which namespace the incoming
 * artifact's classes are actually in (rather than trusting the
 * transform-level source declaration), and namespace-changing steps
 * (remap) update it after producing output so downstream steps see the
 * post-remap truth.
 */
class PipelineState(

    /**
     * The namespace the artifact is currently in. Initialized to the
     * transform's declared source namespace; remap steps must set it to
     * their target namespace after a successful remap.
     */
    var currentNamespace: String,

    /**
     * The environment's side (`CLIENT`/`SERVER`/`MERGED`) when the
     * pipeline is env-scoped, `null` for env-agnostic runs (standalone
     * remap tasks, mod-view transforms).
     */
    val envSide: Side?
)
