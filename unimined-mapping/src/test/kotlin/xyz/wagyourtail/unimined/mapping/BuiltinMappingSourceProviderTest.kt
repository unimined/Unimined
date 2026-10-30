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
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
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
        objects().property(String::class.java).apply { set(channel) },
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
        assertEquals("umf\t1\t3\nofficial", Files.readString(out.files.single()).trim())
    }

    @Test
    fun `mojmap re-emits proguard official-first`() {
        channelFiles["mojmap"] = listOf(file("client_mappings.txt", mojmapTxt))
        val out = provider.materialize(source("mojmap"), ctx)
        val tree = runBlocking { UMFReader.read(Files.readString(out.files.single())) }
        assertEquals(listOf("official", "mojmap"), tree.namespaces.map { it.name })
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))
        assertEquals("net/minecraft/Named", cls?.names?.get(Namespace("mojmap"))?.toString())
    }

    @Test
    fun `searge layers srg member names onto mojmap classes`() {
        channelFiles["mojmap"] = listOf(file("client_mappings.txt", mojmapTxt))
        channelFiles["searge"] = listOf(mcpConfigZip())
        val out = provider.materialize(source("searge", "version" to "1.20.1"), ctx)
        val tree = runBlocking { UMFReader.read(Files.readString(out.files.single())) }
        assertEquals(
            listOf("official", "mojmap", "searge"),
            tree.namespaces.map { it.name }
        )
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))!!
        // class takes the mojmap name, members keep srg names
        assertEquals("net/minecraft/Named", cls.names[Namespace("searge")]?.toString())
        val method = cls.methods.single()
        assertEquals("m_123_", method.names[Namespace("searge")]?.toString())
        // the proguard desc-bearing row and the tsrg desc-less row stay
        // separate (desc-mismatch merge rule) but both carry the searge
        // name — every row must resolve `f_456_`
        assertEquals(
            setOf("f_456_"),
            cls.fields.map { it.names[Namespace("searge")]?.toString() }.toSet()
        )
    }

    @Test
    fun `searge before 1-16-5 keeps srg class names`() {
        channelFiles["searge"] = listOf(mcpConfigZip())
        val out = provider.materialize(source("searge", "version" to "1.15.2"), ctx)
        val tree = runBlocking { UMFReader.read(Files.readString(out.files.single())) }
        assertEquals(listOf("official", "searge"), tree.namespaces.map { it.name })
        val cls = tree.getClass(Namespace("official"), InternalName.read("net/minecraft/a"))!!
        assertEquals("net/minecraft/src/C_1_", cls.names[Namespace("searge")]?.toString())
    }

    private fun forgeSrcZip(path: Path = dir.resolve("forge-src.zip")): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            fun e(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            e(
                "forge/fml/conf/joined.srg", """
                PK: . net/minecraft/src
                CL: a net/minecraft/src/Thing
                FD: a/a net/minecraft/src/Thing/field_1_a
                MD: a/b ()I net/minecraft/src/Thing/func_1_b ()I
                """.trimIndent() + "\n"
            )
            e(
                "forge/fml/conf/fields.csv",
                "searge,name,side,desc\nfield_1_a,chatBuffer,2,\n"
            )
            e(
                "forge/fml/conf/methods.csv",
                "searge,name,side,desc\nfunc_1_b,doThing,2,\n"
            )
            e(
                "forge/fml/conf/params.csv",
                "param,name,side\np_1_1_,par1Foo,2\n"
            )
            e(
                "forge/fml/conf/packages.csv",
                "class,package\nThing,net/minecraft/src\n"
            )
        }
        return path
    }

    @Test
    fun `forgeBuiltinMCP layers forge csvs onto bundled srg`() {
        channelFiles["forgeBuiltinMCP"] = listOf(forgeSrcZip())
        val out = provider.materialize(
            source("forgeBuiltinMCP", "version" to "6.6.2.534"), ctx
        )
        val tree = runBlocking { UMFReader.read(Files.readString(out.files.single())) }
        assertEquals(
            listOf("official", "searge", "forgeMCP"),
            tree.namespaces.map { it.name }
        )
        val cls = tree.getClass(Namespace("official"), InternalName.read("a"))!!
        assertEquals(
            "net/minecraft/src/Thing",
            cls.names[Namespace("searge")]?.toString()
        )
        assertEquals(
            "chatBuffer",
            cls.fields.single().names[Namespace("forgeMCP")]?.toString()
        )
        assertEquals(
            "doThing",
            cls.methods.single().names[Namespace("forgeMCP")]?.toString()
        )
        assertEquals(setOf("forgeMCP"), out.namedNamespaces)
    }

    @Test
    fun `forgeBuiltinMCP reads real fg1 src zips`() {
        val dir = Path.of("/tmp/fg1-smoke")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(dir.resolve("forge-1.4.7-6.6.2.534-src.zip")),
            "real fg1 zips not downloaded"
        )
        for (v in listOf("1.3.2-4.3.5.318", "1.4.7-6.6.2.534", "1.5.2-7.8.1.738")) {
            channelFiles["forgeBuiltinMCP"] =
                listOf(dir.resolve("forge-$v-src.zip"))
            val out = provider.materialize(
                source("forgeBuiltinMCP", "version" to v.substringAfter('-')), ctx
            )
            val tree = runBlocking {
                UMFReader.read(Files.readString(out.files.single()))
            }
            assertEquals(
                listOf("official", "searge", "forgeMCP"),
                tree.namespaces.map { it.name }, "ns order for $v"
            )
            assertTrue(tree.classes.iterator().hasNext(), "no classes for $v")
            // chatBuffer/field_70009_b exists in every fg1 bundle
            var mcpName: String? = null
            outer@ for (c in tree.classes) for (f in c.fields) {
                if (f.names[Namespace("searge")]?.toString() == "field_70009_b") {
                    mcpName = f.names[Namespace("forgeMCP")]?.toString()
                    break@outer
                }
            }
            assertTrue(
                !mcpName.isNullOrEmpty(),
                "forgeMCP name for field_70009_b missing for $v"
            )
        }
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
