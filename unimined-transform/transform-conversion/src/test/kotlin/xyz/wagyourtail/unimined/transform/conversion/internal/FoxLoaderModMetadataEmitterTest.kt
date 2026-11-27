package xyz.wagyourtail.unimined.transform.conversion.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.io.ByteArrayInputStream
import java.util.jar.Manifest

/**
 * FoxLoader's manifest contract changed across the ReIndev 2.9 loader
 * rewrite: 1.x consumes sided `CommonMod`/`ClientMod`/`ServerMod` and
 * mixin attributes, while 2.x consumes `ModMain`/`ModMixin`. The dist
 * version is the discriminator because `foxLoader()` is commonly
 * versionless in the DSL.
 */
class FoxLoaderModMetadataEmitterTest {

    private val emitter = FoxLoaderModMetadataEmitter()

    private fun context(
        metadata: ModMetadata,
        minecraftVersion: String,
        mixinFiles: List<String> = emptyList(),
    ) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = mixinFiles,
        discoveredMixinConfigs = emptyList(),
        gametestEntrypoints = emptyList(),
        clientGametestEntrypoints = emptyList(),
        interfaceInjections = emptyMap(),
        environmentId = "reindev",
        minecraftVersion = minecraftVersion,
        side = "CLIENT",
        devNamespace = "official",
        prodNamespaces = listOf("official")
    )

    private fun metadata(section: LoaderSection? = null) = ModMetadata(
        id = "testmod", name = "Test Mod", version = "1.0.0",
        description = "a mod", authors = listOf("someone"),
        license = "MIT", url = null, icon = null,
        environment = "*", depends = emptyMap(),
        sections = section?.let { mapOf("foxloader" to it) } ?: emptyMap()
    )

    private fun emittedManifest(
        meta: ModMetadata,
        minecraftVersion: String,
        mixinFiles: List<String> = emptyList(),
    ): Manifest {
        val body = emitter.emit(
            context(meta, minecraftVersion, mixinFiles)
        ).getValue(ModMetadataEmitter.MANIFEST_PATH)
        return Manifest(ByteArrayInputStream(body))
    }

    @Test
    fun `foxloader 1 era emits sided entrypoint and mixin attributes`() {
        val section = LoaderSection().apply {
            entrypoint("client", "com.example.ClientEntry")
            entrypoint("server", "com.example.ServerEntry")
        }
        val manifest = emittedManifest(
            metadata(section), "2.8.1_06", listOf("testmod.mixins.json"))
        val attrs = manifest.mainAttributes
        assertEquals("testmod", attrs.getValue("ModId"))
        assertEquals("com.example.ClientEntry", attrs.getValue("ClientMod"))
        assertEquals("com.example.ServerEntry", attrs.getValue("ServerMod"))
        assertEquals("testmod.mixins.json", attrs.getValue("CommonMixin"))
        assertNull(attrs.getValue("ModMain"))
        assertNull(attrs.getValue("ModMixin"))
    }

    @Test
    fun `foxloader 1 main entrypoint maps to common mod`() {
        val section = LoaderSection().apply {
            entrypoint("com.example.CommonEntry")
        }
        val manifest = emittedManifest(metadata(section), "1.0")
        assertEquals("com.example.CommonEntry",
            manifest.mainAttributes.getValue("CommonMod"))
        assertNull(manifest.mainAttributes.getValue("ModMain"))
    }

    @Test
    fun `foxloader 2 era keeps the unified mod attributes`() {
        val section = LoaderSection().apply {
            entrypoint("com.example.MainEntry")
        }
        val manifest = emittedManifest(
            metadata(section), "2.9", listOf("testmod.mixins.json"))
        val attrs = manifest.mainAttributes
        assertEquals("com.example.MainEntry", attrs.getValue("ModMain"))
        assertEquals("testmod.mixins.json", attrs.getValue("ModMixin"))
        assertNull(attrs.getValue("CommonMod"))
        assertNull(attrs.getValue("ClientMod"))
    }

}
