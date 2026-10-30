package xyz.wagyourtail.unimined.transform.fabric.processor

import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.metadata.JavadocElement
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Mod-provided javadoc (`custom."loom:provided_javadoc"`): spec
 * extraction from fmj-declared mods and the comment merge into the
 * composed mapping tree.
 */
class ModJavadocJarProcessorTest {

    @TempDir
    lateinit var dir: Path

    private val processor = ModJavadocJarProcessor()

    /** A mod jar declaring `loom:provided_javadoc` -> [javadocPath]. */
    private fun modJar(
        name: String, modId: String, javadocPath: String?, javadocContent: String?
    ): Path {
        val jar = dir.resolve(name)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("fabric.mod.json"))
            val custom = if (javadocPath != null) {
                ""","custom":{"loom:provided_javadoc":"$javadocPath"}"""
            } else ""
            out.write("""{"id":"$modId"$custom}""".toByteArray())
            out.closeEntry()
            if (javadocPath != null && javadocContent != null) {
                out.putNextEntry(JarEntry(javadocPath))
                out.write(javadocContent.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun specContext(vararg jars: Path) = object : JarProcessor.SpecContext {
        override val productionNamespace = "named"
        override fun mods() = jars.map { FabricModJarInfo(it) }
    }

    @Test
    fun `buildSpec collects fmj-declared javadoc entries`() {
        val jar = modJar(
            "mod-a.jar", "mod-a", "docs/javadoc.tiny",
            "tiny\t2\t0\tnamed\tother\n"
        )
        val spec = processor.buildSpec(specContext(jar)) as ModJavadocJarProcessor.Spec
        val entry = spec.entries.single()
        assertEquals("mod-a", entry.modId)
        assertEquals("mod-a.jar", entry.jarName)
        assertEquals("docs/javadoc.tiny", entry.path)
        assertEquals(64, entry.sha256.length)
    }

    @Test
    fun `buildSpec is null without declarations and skips missing entries`() {
        val noDecl = modJar("mod-b.jar", "mod-b", null, null)
        val missing = modJar("mod-c.jar", "mod-c", "docs/absent.tiny", null)
        assertNull(processor.buildSpec(specContext(noDecl)))
        assertNull(processor.buildSpec(specContext(missing)))
    }

    @Test
    fun `spec serializes and deserializes`() {
        val jar = modJar("mod-d.jar", "mod-d", "j.tiny", "tiny\t2\t0\tnamed\n")
        val spec = processor.buildSpec(specContext(jar))!!
        val parsed = processor.deserializeSpec(processor.serializeSpec(spec))
                as ModJavadocJarProcessor.Spec
        assertEquals("mod-d", parsed.entries.single().modId)
        assertEquals("j.tiny", parsed.entries.single().path)
    }

    @Test
    fun `processMappings merges javadoc comments onto matching rows`() = runBlocking {
        // mod ships a javadoc layer authored in the production namespace
        val jar = modJar(
            "mod-e.jar", "mod-e", "docs/javadoc.tiny",
            "tiny\t2\t0\tnamed\n" +
                    "c\tb/B\n" +
                    "\tc\tdocs for B\n"
        )
        val spec = processor.buildSpec(specContext(jar))!!

        // the composed mappings: official -> named, no javadoc yet
        val tree = TinyV2Reader.read(
            "tiny\t2\t0\tofficial\tnamed\n" +
                    "c\ta/A\tb/B\n" +
                    "\tm\t()V\tm\tn\n"
        )
        val data: MappingData = UmlMappingData(tree)

        val ctx = object : JarProcessor.ProcessorContext {
            override val logger: xyz.wagyourtail.commons.core.logger.Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(ModJavadocJarProcessorTest::class.java))
            override val mappings: MappingData get() = data
            override val mappingContext: MappingContext = object : MappingContext {
                override val environmentId = "test"
                override val logger = this@ModJavadocJarProcessorTest.ctxLogger
                override val cacheDir: Path? = null
                override fun resolveMaven(notation: String): Path = error("no maven")
            }

            override fun mods() = listOf(FabricModJarInfo(jar))
        }

        val out = processor.processMappings().transform(data, spec, ctx)
        val outTree = out.view<MappingTree>()!!
        val cls = outTree.getClass(
            Namespace("named"), InternalName.unchecked("b/B")
        )!!
        val javadoc = cls.metadata.filterIsInstance<JavadocElement>().single()
        assertEquals("docs for B", javadoc.value.toString())
    }

    private val ctxLogger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(ModJavadocJarProcessorTest::class.java))

}
