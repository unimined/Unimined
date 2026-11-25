package xyz.wagyourtail.unimined.transform.jarmod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.jarmod.internal.RiftModMetadataEmitter

class RiftModMetadataEmitterTest {

    private val emitter = RiftModMetadataEmitter()

    private fun context(metadata: ModMetadata) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = emptyList(),
        discoveredMixinConfigs = emptyList(),
        gametestEntrypoints = emptyList(),
        clientGametestEntrypoints = emptyList(),
        interfaceInjections = emptyMap(),
        environmentId = "main",
        minecraftVersion = "1.13.2",
        side = "MERGED",
        devNamespace = "mojmap",
        prodNamespaces = listOf("official")
    )

    private fun metadata(
        sections: Map<String, LoaderSection> = emptyMap()
    ) = ModMetadata(
        id = "testmod", name = "Test Mod", version = "1.0.0",
        description = "a mod", authors = listOf("someone"),
        license = "MIT", url = "https://example.org",
        icon = null, environment = "*", depends = emptyMap(),
        sections = sections
    )

    private fun emitJson(meta: ModMetadata) =
        Json.parseToJsonElement(
            emitter.emit(context(meta)).getValue("riftmod.json")
                .toString(Charsets.UTF_8)
        ).jsonObject

    @Test
    fun `declared entrypoints land in riftmod json`() {
        val section = LoaderSection().apply {
            entrypoint("com.example.TestMod")
        }
        val json = emitJson(metadata(mapOf("rift" to section)))
        val main = json.getValue("entrypoints").jsonObject
            .getValue("main").jsonArray
        assertEquals(
            listOf("com.example.TestMod"),
            main.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `custom entrypoint kinds emit alongside main`() {
        val section = LoaderSection().apply {
            entrypoint("com.example.TestMod")
            entrypoint("server", "com.example.TestModServer")
        }
        val json = emitJson(metadata(mapOf("rift" to section)))
        val entrypoints = json.getValue("entrypoints").jsonObject
        assertEquals(
            listOf("com.example.TestModServer"),
            entrypoints.getValue("server").jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `undeclared section still emits an empty main array`() {
        val json = emitJson(metadata())
        val main = json.getValue("entrypoints").jsonObject
            .getValue("main").jsonArray
        assertTrue(main.isEmpty())
    }

    @Test
    fun `section for a different loader is ignored`() {
        val section = LoaderSection().apply {
            entrypoint("com.example.WrongLoader")
        }
        val json = emitJson(metadata(mapOf("fabric" to section)))
        val main = json.getValue("entrypoints").jsonObject
            .getValue("main").jsonArray
        assertTrue(main.isEmpty())
    }

    private fun manifestAttrs(
        mixinFiles: List<String> = emptyList(),
        discovered: List<String> = emptyList(),
        sections: Map<String, LoaderSection> = emptyMap(),
        envAttrs: Map<String, String> = emptyMap()
    ): java.util.jar.Attributes? {
        val ctx = ModMetadataEmitter.EmitContext(
            metadata = metadata(sections),
            mixinFiles = mixinFiles,
            discoveredMixinConfigs = discovered,
            gametestEntrypoints = emptyList(),
            clientGametestEntrypoints = emptyList(),
            interfaceInjections = emptyMap(),
            environmentId = "main",
            minecraftVersion = "1.13.2",
            side = "MERGED",
            devNamespace = "mojmap",
            prodNamespaces = listOf("official"),
            manifestAttributes = envAttrs
        )
        val bytes = emitter.emitManifest(ctx) ?: return null
        return java.util.jar.Manifest(
            java.io.ByteArrayInputStream(bytes)).mainAttributes
    }

    @Test
    fun `mixin configs emit MixinConfigs and TweakClass attrs`() {
        val attrs = manifestAttrs(
            mixinFiles = listOf("testmod.mixins.json"),
            discovered = listOf("testmod.extra.mixins.json")
        )!!
        assertEquals(
            "testmod.mixins.json,testmod.extra.mixins.json",
            attrs.getValue("MixinConfigs")
        )
        assertEquals(
            "org.spongepowered.asm.launch.MixinTweaker",
            attrs.getValue("TweakClass")
        )
    }

    @Test
    fun `section mixin configs join the manifest refs`() {
        val section = LoaderSection().apply {
            mixinConfigs.add("testmod.section.mixins.json")
        }
        val attrs = manifestAttrs(
            discovered = listOf("testmod.mixins.json"),
            sections = mapOf("rift" to section)
        )!!
        assertEquals(
            "testmod.mixins.json,testmod.section.mixins.json",
            attrs.getValue("MixinConfigs")
        )
    }

    @Test
    fun `no mixin configs emits no manifest`() {
        assertEquals(null, manifestAttrs())
    }

    @Test
    fun `env manifest attributes merge without mixin refs`() {
        val attrs = manifestAttrs(envAttrs = mapOf("Some-Attr" to "x"))!!
        assertEquals("x", attrs.getValue("Some-Attr"))
        assertEquals(null, attrs.getValue("MixinConfigs"))
    }
}
