package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class UmlRemapStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val spec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "named"
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    /** `official -> named`: a/A -> b/B, field f -> g, method m -> n. */
    private val tree = runBlocking {
        TinyV2Reader.read(
            """
            tiny	2	0	official	named
            c	a/A	b/B
            	f	I	f	g
            	m	()V	m	n
            """.trimIndent()
        )
    }

    private val view = object : UmlMappingTreeView {
        override val tree get() = this@UmlRemapStepTypeTest.tree
    }

    private val mappings = object : MappingData {
        override val viewTypes = setOf(UmlMappingTreeView::class)

        @Suppress("UNCHECKED_CAST")
        override fun <T : MappingDataView> view(type: Class<T>): T? =
            if (type == UmlMappingTreeView::class.java) view as T else null
    }

    private fun ctx(): StepContext = object : StepContext {
        override val spec = this@UmlRemapStepTypeTest.spec
        override val mappings = this@UmlRemapStepTypeTest.mappings
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = Logging.getLogger(UmlRemapStepTypeTest::class.java)
    }

    /** Compile `a.A` (field f, method m) into a jar; return its path. */
    private fun inputJar(): Path {
        val srcDir = dir.resolve("src/a")
        Files.createDirectories(srcDir)
        val java = srcDir.resolve("A.java")
        Files.writeString(java, "package a; public class A { public int f; public void m() {} }")
        val classes = dir.resolve("classes")
        Files.createDirectories(classes)
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", classes.toString(), java.toString())
        assertEquals(0, rc)
        val jar = dir.resolve("in.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("a/A.class"))
            Files.copy(classes.resolve("a/A.class"), out)
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `registry discovers uml via ServiceLoader`() {
        val type = RemapStepRegistry().resolve(RemapStepSpec(), RemapSurface.JAR, spec)
        assertEquals("uml", type.name)
    }

    @Test
    fun `remaps a jar through the step`() {
        val ctx = ctx()
        val out = UmlRemapStepType().create(RemapStepSpec(), ctx)
            .apply(inputJar(), ctx)

        JarFile(out.toFile()).use { jar ->
            assertNotNull(jar.getEntry("b/B.class"))
            assertNull(jar.getEntry("a/A.class"))
            val bytes = jar.getInputStream(jar.getEntry("b/B.class")).readBytes()
            assertTrue(bytes.containsAscii("b/B"))
            assertTrue(!bytes.containsAscii("a/A"))
        }
    }

    @Test
    fun `supports the decompile surface`() {
        assertTrue(RemapSurface.DECOMPILE in UmlRemapStepType().surfaces)
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }

}
