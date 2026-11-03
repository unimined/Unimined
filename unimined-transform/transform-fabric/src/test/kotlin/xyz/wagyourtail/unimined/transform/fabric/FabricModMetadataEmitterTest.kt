package xyz.wagyourtail.unimined.transform.fabric

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModMetadataEmitter

class FabricModMetadataEmitterTest {

    private val emitter = FabricModMetadataEmitter()

    private fun context(
        metadata: ModMetadata,
        mixinFiles: List<String> = emptyList()
    ) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = mixinFiles,
        environmentId = "main",
        minecraftVersion = "1.20.1",
        side = "MERGED",
        devNamespace = "mojmap",
        prodNamespaces = listOf("intermediary")
    )

    private fun metadata(
        sections: Map<String, LoaderSection> = emptyMap(),
        depends: Map<String, String> = emptyMap()
    ) = ModMetadata(
        id = "testmod", name = "Test Mod", version = "1.0.0",
        description = "a mod", authors = listOf("someone"),
        license = "MIT", url = "https://example.org",
        icon = null, environment = "*", depends = depends,
        sections = sections
    )

    @Test
    fun `emits fabric mod json with model fields`() {
        val section = LoaderSection().apply {
            entrypoint("main", "com.example.Mod")
            entrypoint("client", "com.example.ModClient")
            accessWidener("testmod.accesswidener")
        }
        val out = emitter.emit(
            context(
                metadata(
                    sections = mapOf("fabric" to section),
                    depends = mapOf("minecraft" to "~1.20.1")
                ),
                mixinFiles = listOf("testmod.mixins.json")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject

        assertEquals(1, json["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("testmod", json["id"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", json["version"]!!.jsonPrimitive.content)
        assertEquals("Test Mod", json["name"]!!.jsonPrimitive.content)
        assertEquals("MIT", json["license"]!!.jsonPrimitive.content)
        assertEquals("*", json["environment"]!!.jsonPrimitive.content)
        assertEquals(
            "https://example.org",
            json["contact"]!!.jsonObject["homepage"]!!.jsonPrimitive.content
        )
        assertEquals(
            listOf("com.example.Mod"),
            json["entrypoints"]!!.jsonObject["main"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("testmod.mixins.json"),
            json["mixins"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            "~1.20.1",
            json["depends"]!!.jsonObject["minecraft"]!!.jsonPrimitive.content
        )
        assertEquals(
            "testmod.accesswidener",
            json["accessWidener"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `section environment overrides the model environment`() {
        val section = LoaderSection().apply { environment("client") }
        val out = emitter.emit(
            context(metadata(sections = mapOf("fabric" to section)))
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        assertEquals("client", json["environment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `manual mixin config refs merge with generated files`() {
        val section = LoaderSection().apply { mixinConfig("extra.mixins.json") }
        val out = emitter.emit(
            context(
                metadata(sections = mapOf("fabric" to section)),
                mixinFiles = listOf("testmod.mixins.json")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        assertEquals(
            listOf("testmod.mixins.json", "extra.mixins.json"),
            json["mixins"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

}
