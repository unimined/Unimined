package xyz.wagyourtail.unimined.mod

import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.io.ByteArrayOutputStream
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * Test-only emitter with both a descriptor document and a manifest body —
 * exercises emission gating without pulling a transform plugin onto the
 * `unimined-mod` test classpath.
 */
class TestManifestEmitter : ModMetadataEmitter {
    override val loader = "testmanifest"

    override fun emit(
        context: ModMetadataEmitter.EmitContext
    ): Map<String, ByteArray> =
        mapOf("testmod.manifestloader" to "generated\n".toByteArray())

    override fun emitManifest(context: ModMetadataEmitter.EmitContext): ByteArray? {
        val mixins = context.mixinFiles + context.discoveredMixinConfigs
        if (mixins.isEmpty()) return null
        val mf = Manifest()
        mf.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mf.mainAttributes.putValue("MixinConfigs", mixins.joinToString(","))
        return ByteArrayOutputStream().also { mf.write(it) }.toByteArray()
    }
}
