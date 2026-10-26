package xyz.wagyourtail.unimined.mapping

import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.BuiltinMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.sources.BuiltinMappingSourceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuiltinMappingSourceProviderTest {

    @TempDir
    lateinit var dir: Path

    private fun objects() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .objects

    private val channelFiles = mutableMapOf<String, List<Path>>()

    private val ctx = object : MappingContext {
        override val environmentId = "test"
        override val logger: Logger = Logging.getLogger(BuiltinMappingSourceProviderTest::class.java)
        override val cacheDir: Path get() = dir
        override fun resolveMaven(notation: String): Path =
            throw UnsupportedOperationException("no maven resolution in tests")
        override fun resolveChannel(channel: String, params: Map<String, String>): List<Path> =
            channelFiles[channel] ?: emptyList()
    }

    private val provider = BuiltinMappingSourceProvider()

    private fun source(
        channel: String,
        vararg params: Pair<String, String>
    ): BuiltinMappingSource = MappingsSpecImpl.Source.Builtin(
        channel,
        params.associate { (k, v) ->
            k to objects().property(String::class.java).apply { set(v) }
        }
    )

    private fun file(name: String, content: String): Path {
        val f = dir.resolve(name)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
        return f
    }

    private fun zip(name: String, vararg entries: Pair<String, String>): Path {
        val f = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(f)).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    private val mojmapTxt = """
        net.minecraft.Named -> net/minecraft/a:
            int doIt() -> m_a
            int thing -> f_a
    """.trimIndent()

    private fun mcpConfigZip() = zip(
        "mcp_config.zip",
        "config/joined.tsrg" to """
            tsrg2 obf srg id
            net/minecraft/a net/minecraft/src/C_1_ 1
            	m_a ()I m_123_ 123
            	f_a f_456_ 456
        """.trimIndent() + "\n"
    )

    @Test
    fun `official materializes an identity document`() {
        val out = provider.materialize(source("official"), ctx)
        assertEquals("tiny\t2\t0\tofficial", Files.readString(out.files.single()).trim())
    }

    @Test
    fun `mojmap re-emits proguard official-first`() {
        channelFiles["mojmap"] = listOf(file("client_mappings.txt", mojmapTxt))
        val out = provider.materialize(source("mojmap"), ctx)
        val tree = runBlocking { TinyV2Reader.read(Files.readString(out.files.single())) }
        assertEquals(listOf("official", "mojmap"), tree.namespaces.map { it.name })
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))
        assertEquals("net/minecraft/Named", cls?.names?.get(Namespace("mojmap"))?.toString())
    }

    @Test
    fun `searge layers srg member names onto mojmap classes`() {
        channelFiles["mojmap"] = listOf(file("client_mappings.txt", mojmapTxt))
        channelFiles["searge"] = listOf(mcpConfigZip())
        val out = provider.materialize(source("searge", "version" to "1.20.1"), ctx)
        val tree = runBlocking { TinyV2Reader.read(Files.readString(out.files.single())) }
        assertEquals(
            listOf("official", "mojmap", "searge"),
            tree.namespaces.map { it.name }
        )
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))!!
        // class takes the mojmap name, members keep srg names
        assertEquals("net/minecraft/Named", cls.names[Namespace("searge")]?.toString())
        val method = cls.methods.single()
        assertEquals("m_123_", method.names[Namespace("searge")]?.toString())
        val field = cls.fields.single()
        assertEquals("f_456_", field.names[Namespace("searge")]?.toString())
    }

    @Test
    fun `searge before 1-16-5 keeps srg class names`() {
        channelFiles["searge"] = listOf(mcpConfigZip())
        val out = provider.materialize(source("searge", "version" to "1.15.2"), ctx)
        val tree = runBlocking { TinyV2Reader.read(Files.readString(out.files.single())) }
        assertEquals(listOf("official", "searge"), tree.namespaces.map { it.name })
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))!!
        assertEquals("net/minecraft/src/C_1_", cls.names[Namespace("searge")]?.toString())
    }

    @Test
    fun `parchment extracts parchment json with mojmap relabel`() {
        channelFiles["parchment"] = listOf(
            zip("parchment.zip", "parchment.json" to """{"version":"1.1.0"}""")
        )
        val out = provider.materialize(source("parchment", "version" to "1.1.0"), ctx)
        assertEquals("parchment", out.formatHint)
        assertEquals("mojmap", out.nsMap["source"])
        assertTrue(
            out.files.single().fileName.toString()
                .matches(Regex("parchment-1\\.1\\.0-[0-9a-f]{12}\\.json"))
        )
    }

}
