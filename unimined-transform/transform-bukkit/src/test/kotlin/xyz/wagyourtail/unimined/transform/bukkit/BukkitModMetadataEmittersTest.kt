package xyz.wagyourtail.unimined.transform.bukkit

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.bukkit.internal.CraftBukkitModMetadataEmitter
import xyz.wagyourtail.unimined.transform.bukkit.internal.PaperModMetadataEmitter
import xyz.wagyourtail.unimined.transform.bukkit.internal.SpigotModMetadataEmitter

class BukkitModMetadataEmittersTest {

    private fun context(metadata: ModMetadata) = ModMetadataEmitter.EmitContext(
        metadata = metadata,
        mixinFiles = emptyList(),
        environmentId = "main",
        minecraftVersion = "1.20.1",
        side = "MERGED",
        devNamespace = "mojmap",
        prodNamespaces = listOf("mojmap")
    )

    private fun metadata(sections: Map<String, LoaderSection>) = ModMetadata(
        id = "testplugin", name = "Test Plugin", version = "1.0.0",
        description = "a plugin", authors = listOf("someone", "else"),
        license = null, url = null, icon = null, environment = null,
        depends = emptyMap(), sections = sections
    )

    private fun section(loader: String, init: LoaderSection.() -> Unit = {}): Map<String, LoaderSection> =
        mapOf(loader to LoaderSection().apply { entrypoint("com.example.TestPlugin"); init() })

    private fun emitted(content: Map<String, ByteArray>): Map<*, *> =
        Yaml().load(content.values.single().toString(Charsets.UTF_8)) as Map<*, *>

    @Test
    fun `spigot emitter produces a parseable plugin yml`() {
        val out = SpigotModMetadataEmitter().emit(context(metadata(section("spigot"))))
        assertEquals(setOf("plugin.yml"), out.keys)
        val yml = emitted(out)
        assertEquals("Test Plugin", yml["name"])
        assertEquals("com.example.TestPlugin", yml["main"])
        assertEquals("1.0.0", yml["version"])
        assertEquals("1.20.1", yml["api-version"])
        assertEquals("a plugin", yml["description"])
        assertEquals(listOf("someone", "else"), yml["authors"])
    }

    @Test
    fun `craftbukkit emitter shares the plugin yml shape`() {
        val out = CraftBukkitModMetadataEmitter().emit(context(metadata(section("craftbukkit"))))
        assertEquals("plugin.yml", out.keys.single())
    }

    @Test
    fun `paper emits paper-plugin yml with type PAPER`() {
        val section = section("paper") {
            entrypoint("bootstrapper", "com.example.Boot")
        }
        val out = PaperModMetadataEmitter().emit(context(metadata(section)))
        assertEquals("paper-plugin.yml", out.keys.single())
        val yml = emitted(out)
        assertEquals("PAPER", yml["type"])
        assertEquals("com.example.Boot", yml["bootstrapper"])
        assertEquals("com.example.TestPlugin", yml["main"])
    }

    @Test
    fun `missing main entrypoint is a clear failure`() {
        val e = assertThrows(GradleException::class.java) {
            SpigotModMetadataEmitter().emit(context(metadata(mapOf())))
        }
        assertTrue(e.message!!.contains("main"), e.message)
    }

    @Test
    fun `custom keys emit verbatim`() {
        val section = section("spigot") {
            custom("depend", "SomeOtherPlugin")
            custom("load", "STARTUP")
        }
        val yml = emitted(SpigotModMetadataEmitter().emit(context(metadata(section))))
        assertEquals("SomeOtherPlugin", yml["depend"])
        assertEquals("STARTUP", yml["load"])
    }

}
