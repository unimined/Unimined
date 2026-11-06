package xyz.wagyourtail.unimined.transform.fabric

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
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModMetadataEmitter

class FabricModMetadataEmitterTest {

    private val emitter = FabricModMetadataEmitter()

    private fun context(
        metadata: ModMetadata,
        mixinFiles: List<String> = emptyList(),
        discoveredMixinConfigs: List<String> = emptyList(),
        gametestEntrypoints: List<String> = emptyList(),
        clientGametestEntrypoints: List<String> = emptyList(),
        interfaceInjections: Map<String, List<String>> = emptyMap(),
        classTweakerRefs: List<String> = emptyList()
    ) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = mixinFiles,
        discoveredMixinConfigs = discoveredMixinConfigs,
        gametestEntrypoints = gametestEntrypoints,
        clientGametestEntrypoints = clientGametestEntrypoints,
        interfaceInjections = interfaceInjections,
        classTweakerRefs = classTweakerRefs,
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

    @Test
    fun `discovered mixin configs join generated refs`() {
        val out = emitter.emit(
            context(
                metadata(),
                mixinFiles = listOf("testmod.mixins.json"),
                discoveredMixinConfigs = listOf("com/example/manual.mixins.json")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        assertEquals(
            listOf("testmod.mixins.json", "com/example/manual.mixins.json"),
            json["mixins"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `gametest entrypoints land under fabric-gametest`() {
        val out = emitter.emit(
            context(
                metadata(),
                gametestEntrypoints = listOf("com.example.MyGameTest")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        assertEquals(
            listOf("com.example.MyGameTest"),
            json["entrypoints"]!!.jsonObject["fabric-gametest"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `client gametest entrypoints land under fabric-client-gametest`() {
        val out = emitter.emit(
            context(
                metadata(),
                clientGametestEntrypoints = listOf("com.example.MyClientGameTest")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        assertEquals(
            listOf("com.example.MyClientGameTest"),
            json["entrypoints"]!!.jsonObject["fabric-client-gametest"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `interface injections emit loom key and classTweakers refs`() {
        val out = emitter.emit(
            context(
                metadata(),
                interfaceInjections = mapOf(
                    "net/minecraft/class_161" to listOf("com/example/ExampleInterface")
                ),
                classTweakerRefs = listOf("META-INF/unimined/injected-interfaces.ct")
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        val injected = json["custom"]!!.jsonObject["loom:injected_interfaces"]!!
            .jsonObject
        assertEquals(
            listOf("com/example/ExampleInterface"),
            injected["net/minecraft/class_161"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("META-INF/unimined/injected-interfaces.ct"),
            json["classTweakers"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `generated injections merge with manually declared custom object`() {
        // a hand-authored fmj may already carry loom:injected_interfaces —
        // the model-level custom map can't express objects, so this path
        // only matters for the physical-file merge (covered by
        // MetadataJsonMergeTest); here a String custom of the same key
        // is simply overwritten by the structured value
        val section = LoaderSection().apply {
            custom("other.key", "value")
        }
        val out = emitter.emit(
            context(
                metadata(sections = mapOf("fabric" to section)),
                interfaceInjections = mapOf(
                    "net/minecraft/class_161" to listOf("com/example/I")
                )
            )
        )
        val json = Json.parseToJsonElement(
            out["fabric.mod.json"]!!.decodeToString()
        ).jsonObject
        val custom = json["custom"]!!.jsonObject
        assertEquals("value", custom["other.key"]!!.jsonPrimitive.content)
        assertTrue(
            custom.containsKey("loom:injected_interfaces"),
            "generated injections must emit the loom key"
        )
    }

}
