package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.transform.spi.TransformOutput

/** Plain [TransformOutput] over a named artifact provider. */
internal class RuntimeTransformOutput(
    override val name: String,
    override val artifact: Provider<RegularFile>
) : TransformOutput
