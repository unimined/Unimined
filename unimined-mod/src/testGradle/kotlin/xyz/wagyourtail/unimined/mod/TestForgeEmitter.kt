package xyz.wagyourtail.unimined.mod

import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter

/**
 * Test-only emitter registered via `src/testGradle/resources/META-INF/services`
 * — exercises the AT-family access conversion path without pulling
 * transform-forge onto the test classpath.
 */
class TestForgeEmitter : ModMetadataEmitter {
    override val loader = "testforge"
    override val accessFormat = "at"
    override val accessPath = "META-INF/accesstransformer.cfg"

    override fun emit(
        context: ModMetadataEmitter.EmitContext
    ): Map<String, ByteArray> = emptyMap()
}
