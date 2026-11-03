package xyz.wagyourtail.unimined.remap.internal

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import java.nio.file.Path

/**
 * Execution-time [RemapSpec] assembled from a task's resolved inputs.
 */
internal class RuntimeRemapSpec(
    override val sourceNamespace: String,
    override val targetNamespace: String,
    override val classpath: List<Path>
) : RemapSpec
