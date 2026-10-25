package xyz.wagyourtail.unimined.transform

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * The result of running the declared transformer pipeline: all emitted
 * named artifacts plus the final dev artifact.
 */
class TransformPipeline internal constructor(
    /** Every emitted output, keyed by name. `"jar"` is the main dev
     *  artifact of the last transformer (decorators applied). */
    val outputs: Map<String, Provider<RegularFile>>,
    /** The final dev artifact — the decorated output of the last
     *  transformer in declaration order. */
    val output: Provider<RegularFile>
) {

    /** Look up a named output. */
    fun output(name: String): Provider<RegularFile>? = outputs[name]

}
