package xyz.wagyourtail.unimined.transform.spi

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Cross-cutting transform applied on top of any loader transform —
 * access wideners, access transformers, interface injection, mixin
 * refmap. Chained like remap steps, but scoped to the MC dev artifact.
 */
interface TransformDecorator {

    fun apply(input: Provider<RegularFile>, ctx: TransformContext): Provider<RegularFile>

}
