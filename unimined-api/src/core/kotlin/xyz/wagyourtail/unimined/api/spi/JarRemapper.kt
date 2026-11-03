package xyz.wagyourtail.unimined.api.spi

import xyz.wagyourtail.unimined.api.mapping.MappingData
import java.nio.file.Path

/**
 * Whole-jar binary remapper — ServiceLoader extension point so
 * consumers outside `unimined-remap` (e.g. the environment artifact
 * transform driving a remap-before-merge for split-side
 * environments) can remap a jar without a compile dependency on a
 * remapper engine. Implementations register via
 * `META-INF/services/xyz.wagyourtail.unimined.api.spi.JarRemapper`
 * and are resolved at execution time.
 */
interface JarRemapper {

    /**
     * Remap [input] (a jar in the [fns] namespace) into the [tns]
     * namespace using [mappings], writing [output]. Namespace names
     * are resolved against the mapping data's views by the
     * implementation.
     */
    fun remap(
        input: Path,
        output: Path,
        mappings: MappingData,
        fns: String,
        tns: String,
        env: String? = null
    )

}
