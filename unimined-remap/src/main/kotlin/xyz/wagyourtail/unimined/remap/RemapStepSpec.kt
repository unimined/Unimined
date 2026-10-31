package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * DSL spec of the namespace-changing `remap` step — at most one per
 * surface. [engine] names the remap engine (`"tiny"`, `"uml"`, ...);
 * `null` defers to capability selection.
 */
class RemapStepSpec(
    @get:Input @get:Optional override val engine: String? = null,
    /** rewrite JSR-308/JSR-305 annotation usages to
     *  `org.jetbrains.annotations` equivalents — sources and bytecode */
    @get:Input val jsr308ToJetbrains: Boolean = true
) : StepSpec
