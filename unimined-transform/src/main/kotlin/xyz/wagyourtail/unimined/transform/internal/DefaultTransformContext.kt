package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.spi.TransformDecorator
import xyz.wagyourtail.unimined.transform.spi.TransformOutput

/**
 * Pipeline-side [TransformContext]: prior outputs plus the emitted-artifact
 * collector transforms feed via [emit].
 */
internal class DefaultTransformContext(
    override val environmentId: String,
    override val minecraft: MinecraftArtifacts,
    override val mappings: MappingsSpec,
    override val inputs: List<TransformOutput>,
    override val decorators: List<TransformDecorator>
) : TransformContext {

    /** Artifacts emitted by this transform, in emission order. */
    val emitted = linkedMapOf<String, Provider<RegularFile>>()

    override fun emit(name: String, artifact: Provider<RegularFile>) {
        require(name !in emitted) { "transform emitted duplicate output '$name'" }
        emitted[name] = artifact
    }

}
