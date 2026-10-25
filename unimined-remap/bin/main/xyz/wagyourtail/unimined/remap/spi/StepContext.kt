package xyz.wagyourtail.unimined.remap.spi

import org.gradle.api.logging.Logger
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

    /** Scratch directory for step intermediates. */
    val workDir: Path

    val logger: Logger

}
