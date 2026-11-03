package xyz.wagyourtail.unimined.transform.spi

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * One named output in the transform pipeline — feeds
 * [TransformContext.inputs] of later transforms.
 */
interface TransformOutput {

    val name: String

    val artifact: Provider<RegularFile>

}
