package xyz.wagyourtail.unimined.transform.fabric

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.fabric.internal.metadata.QuiltModMetadataEmitter

class QuiltModMetadataEmitterTest {

    private val emitter = QuiltModMetadataEmitter()

    private fun context(
        metadata: ModMetadata,
        mixinFiles: List<String> = emptyList(),
        discoveredMixinConfigs: List<String> = emptyList(),
        interfaceInjections: Map<String, List<String>> = emptyMap()
    ) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = mixinFiles,
        discoveredMixinConfigs = discoveredMixinConfigs,
        interfaceInjections = interfaceInjections,
        environmentId = "main",
        minecraftVersion = "1.20.1",
        side = "MERGED",
        devNamespace = "mojmap",
        prodNamespaces = listOf("intermediary"),
        mavenGroup = "com.example"
    )

    private fun metadata(
        sections: Map<String, LoaderSection> = emptyMap(),
        depends: Map<String, String> = emptyMap(),
        environment: String? = "*"
    ) = ModMetadata(
        id = "testmod", name = "Test Mod", version = "1.0.0",
        description = "a mod", authors = listOf("someone"),
        license = "MIT", url = "https://example.org",
        icon = null, environment = environment, depends = depends,
        sections = sections
    )

    private fun emit(context: ModMetadataEmitter.EmitContext) =
        Json.parseToJsonElement(
            emitter.emit(context)["quilt.mod.json"]!!.decodeToString()
        ).jsonObject

    @Test
    fun `emits real qmj — schema_version + quilt_loader group + metadata`() {
        val section = LoaderSection().apply {
            entrypoint("main", "com.example.Mod")
            entrypoint("client", "com.example.ModClient")
        }
        val json = emit(
            context(
                metadata(
                    sections = mapOf("quilt" to section),
                    depends = mapOf("minecraft" to "~1.20.1")
                ),
                mixinFiles = listOf("testmod.mixins.json")
            )
        )

        // root shape — fmj keys must NOT leak
        assertEquals(1, json["schema_version"]!!.jsonPrimitive.content.toInt())
        assertFalse(json.containsKey("schemaVersion"))
        assertFalse(json.containsKey("id"))
        assertFalse(json.containsKey("custom"))
        val loader = json["quilt_loader"]!!.jsonObject
        // `group` is REQUIRED by quilt-loader's reader
        assertEquals("com.example", loader["group"]!!.jsonPrimitive.content)
        assertEquals("testmod", loader["id"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", loader["version"]!!.jsonPrimitive.content)
        val metadata = loader["metadata"]!!.jsonObject
        assertEquals("Test Mod", metadata["name"]!!.jsonPrimitive.content)
        assertEquals("a mod", metadata["description"]!!.jsonPrimitive.content)
        assertEquals("MIT", metadata["license"]!!.jsonPrimitive.content)
        assertEquals(
            "https://example.org",
            metadata["contact"]!!.jsonObject["homepage"]!!.jsonPrimitive.content
        )
        assertEquals(
            "Owner",
            metadata["contributors"]!!.jsonObject["someone"]!!.jsonPrimitive.content
        )
        assertEquals(
            listOf("com.example.Mod"),
            loader["entrypoints"]!!.jsonObject["main"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
        // qmj depends is an ARRAY of {id, versions} objects
        val depends = loader["depends"]!!.jsonArray.single().jsonObject
        assertEquals("minecraft", depends["id"]!!.jsonPrimitive.content)
        assertEquals("~1.20.1", depends["versions"]!!.jsonPrimitive.content)
        // intermediary prod ns → intermediate_mappings declaration
        assertEquals(
            "net.fabricmc:intermediary",
            loader["intermediate_mappings"]!!.jsonPrimitive.content
        )
        // mixin + environment are top-level qmj keys
        assertEquals(
            listOf("testmod.mixins.json"),
            json["mixin"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            "*",
            json["minecraft"]!!.jsonObject["environment"]!!.jsonPrimitive.content
        )
        assertFalse(loader.containsKey("environment"))
    }

    @Test
    fun `server environment translates to dedicated_server`() {
        val json = emit(context(metadata(environment = "server")))
        assertEquals(
            "dedicated_server",
            json["minecraft"]!!.jsonObject["environment"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `section environment overrides and translates`() {
        val section = LoaderSection().apply { environment("server") }
        val json = emit(
            context(metadata(sections = mapOf("quilt" to section)))
        )
        assertEquals(
            "dedicated_server",
            json["minecraft"]!!.jsonObject["environment"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `access widener emits access_widener top level`() {
        val section = LoaderSection().apply {
            accessWidener("testmod.accesswidener")
        }
        val json = emit(
            context(metadata(sections = mapOf("quilt" to section)))
        )
        assertEquals(
            "testmod.accesswidener",
            json["access_widener"]!!.jsonPrimitive.content
        )
        assertFalse(json.containsKey("accessWidener"))
        assertFalse(
            json["quilt_loader"]!!.jsonObject.containsKey("access_widener"))
    }

    @Test
    fun `interface injections emit top-level quilt_loom object`() {
        val json = emit(
            context(
                metadata(),
                interfaceInjections = mapOf(
                    "net/minecraft/class_161" to
                            listOf("com/example/ExampleInterface")
                )
            )
        )
        val injected = json["quilt_loom"]!!.jsonObject["injected_interfaces"]!!
            .jsonObject
        assertEquals(
            listOf("com/example/ExampleInterface"),
            injected["net/minecraft/class_161"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertFalse(json.containsKey("custom"))
        assertFalse(json.containsKey("loom:injected_interfaces"))
    }

    @Test
    fun `section custom entries land as top-level namespaced keys`() {
        val section = LoaderSection().apply {
            custom("modmenu:api", "true")
        }
        val json = emit(
            context(metadata(sections = mapOf("quilt" to section)))
        )
        assertEquals("true", json["modmenu:api"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("custom"))
    }

    @Test
    fun `discovered mixin configs join generated refs`() {
        val json = emit(
            context(
                metadata(),
                mixinFiles = listOf("testmod.mixins.json"),
                discoveredMixinConfigs = listOf("com/example/manual.mixins.json")
            )
        )
        assertEquals(
            listOf("testmod.mixins.json", "com/example/manual.mixins.json"),
            json["mixin"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `metadata object omits empty fields`() {
        val json = emit(
            context(
                ModMetadata(
                    id = "bare", name = null, version = null,
                    description = null, authors = emptyList(),
                    license = null, url = null, icon = null,
                    environment = null, depends = emptyMap(),
                    sections = emptyMap()
                )
            )
        )
        val metadata = json["quilt_loader"]!!.jsonObject["metadata"]!!.jsonObject
        assertNull(metadata["name"])
        assertNull(metadata["contributors"])
        assertNull(metadata["contact"])
        assertNull(metadata["license"])
        // no environment anywhere when unset
        assertNull(json["minecraft"])
    }

}
