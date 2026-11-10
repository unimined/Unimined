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
        classTweakerRefs = emptyList(),
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
}
