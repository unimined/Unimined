package xyz.wagyourtail.unimined.transform.spi

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts

/**
 * Everything a [MinecraftTransformer] sees at configure time: provider
 * output, mappings declaration, prior pipeline outputs, and the
 * cross-cutting decorators to apply.
 */
interface TransformContext {

    /** Source-set/environment whose pipeline is being configured. */
    val environmentId: String
        get() = "main"

    /** Provider output — the resolved environment artifacts. */
    val minecraft: MinecraftArtifacts

    val mappings: MappingsSpec

    /** Prior transform outputs, in pipeline order. */
    val inputs: List<TransformOutput>

    /** AW/AT/interface-injection/mixin-refmap decorators to apply on top
     *  of the loader transform. */
    val decorators: List<TransformDecorator>

    /** Emit a named artifact produced by this transform. */
    fun emit(name: String, artifact: Provider<RegularFile>)

}
