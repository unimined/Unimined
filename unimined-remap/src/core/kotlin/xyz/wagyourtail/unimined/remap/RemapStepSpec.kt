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
    /** rewrite JSR-305-era nullness annotation usages to
     *  `org.jetbrains.annotations` equivalents — sources and bytecode */
    @get:Input val jsr305ToJetbrains: Boolean = true
) : StepSpec {

    /** Rebuild missing `InnerClasses` entries after remap — 1.x
     *  `fixInners` (obf-era, pre-1.8.2 MC jars). `null` = the pipeline
     *  consumer's default: the env-artifact pipeline substitutes
     *  `env.obfuscated && mc < 1.8.2`; mod-jar/dep-view pipelines
     *  leave it unset (engines default off — 1.x never ran it there). */
    @get:Input
    @get:Optional
    var fixInnerClasses: Boolean? = null

}
