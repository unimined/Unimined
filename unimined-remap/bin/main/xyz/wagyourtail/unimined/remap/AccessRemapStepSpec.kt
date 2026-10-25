package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `remap-access` step. Rewrites access-modification resources
 * embedded in the jar so they refer to the target namespace after a
 * namespace-changing `remap` step.
 *
 * [formats] selects the resource grammars to process (`"aw"`, `"at"`,
 * `"ct"`). [paths] may name additional jar entries containing one of those
 * grammars when loader metadata declares a non-standard path.
 */
class AccessRemapStepSpec(
    override val engine: String? = null
) : StepSpec {

    /** Enabled resource formats: `aw`, `at`, and/or `ct`. */
    @get:Input
    var formats: Set<String> = setOf("aw", "at", "ct")

    /** Additional jar-entry paths to inspect for access metadata. */
    @get:Input
    var paths: Set<String> = emptySet()

}
