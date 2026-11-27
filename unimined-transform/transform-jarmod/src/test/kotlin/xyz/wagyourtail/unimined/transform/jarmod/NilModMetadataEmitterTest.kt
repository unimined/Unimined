package xyz.wagyourtail.unimined.transform.jarmod

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.jarmod.internal.NilModMetadataEmitter

/** qdcss emission for NilLoader metadata, especially string escaping. */
class NilModMetadataEmitterTest {

    private fun emit(metadata: ModMetadata): String {
        val context = ModMetadataEmitter.EmitContext(
            metadata = metadata,
            mixinFiles = emptyList(),
            discoveredMixinConfigs = emptyList(),
            gametestEntrypoints = emptyList(),
            clientGametestEntrypoints = emptyList(),
            interfaceInjections = emptyMap(),
            environmentId = "main",
            minecraftVersion = "1.4.7",
            side = "CLIENT",
            devNamespace = "feather",
            prodNamespaces = listOf("official")
        )
        return NilModMetadataEmitter().emit(context)
            .getValue("testmod.nilmod.css").toString(Charsets.UTF_8)
    }

    private fun metadata(section: LoaderSection? = null) = ModMetadata(
        id = "testmod",
        name = "Test \"Quoted\" Mod",
        version = "1.0\\test",
        description = "line one\nline two",
        authors = listOf("A \"Writer\""),
        license = "MIT",
        url = null,
        icon = null,
        environment = "*",
        depends = emptyMap(),
        sections = section?.let { mapOf("nil" to it) }.orEmpty()
    )

    @Test
    fun `quotes backslashes and newlines are escaped in qdcss strings`() {
        val css = emit(metadata())
        assertTrue(css.contains("name: \"Test \\\"Quoted\\\" Mod\";"), css)
        assertTrue(css.contains("version: \"1.0\\\\test\";"), css)
        assertTrue(css.contains("description: \"line one\\nline two\";"), css)
        assertTrue(css.contains("authors: \"A \\\"Writer\\\"\";"), css)
    }

    @Test
    fun `entrypoint class names emit escaped qdcss strings`() {
        val section = LoaderSection().apply {
            entrypoint("premain", "example.TestEntrypoint")
        }
        val css = emit(metadata(section))
        assertTrue(css.contains("premain: \"example.TestEntrypoint\";"), css)
    }
}
