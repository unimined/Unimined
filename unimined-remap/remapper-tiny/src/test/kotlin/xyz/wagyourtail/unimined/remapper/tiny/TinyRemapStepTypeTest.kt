package xyz.wagyourtail.unimined.remapper.tiny

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
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.spec.RemapSpec
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

class TinyRemapStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val spec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "named"
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    /** `official -> named`: a/A -> b/B, field f -> g, method m -> n. */
    private val tree = object : MappingTree {
        override val namespaces = listOf("official", "named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
            visitor.visitClass("a/A", "b/B")
            visitor.visitField("a/A", "f", "I", "g")
            visitor.visitMethod("a/A", "m", "()V", "n")
        }
    }

    private val mappings = object : MappingData {
        override val viewTypes = setOf(MappingTree::class)

        @Suppress("UNCHECKED_CAST")
        override fun <T : MappingDataView> view(type: Class<T>): T? =
            if (type == MappingTree::class.java) tree as T else null
    }

    private fun ctx(): StepContext = object : StepContext {
        override val spec = this@TinyRemapStepTypeTest.spec
        override val mappings = this@TinyRemapStepTypeTest.mappings
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = Logging.getLogger(TinyRemapStepTypeTest::class.java)
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
    fun `registry discovers tiny via ServiceLoader`() {
        val type = RemapStepRegistry().resolve(RemapStepSpec(), RemapSurface.JAR, spec)
        assertEquals("tiny", type.name)
    }

    @Test
    fun `remaps a jar through the step`() {
        val ctx = ctx()
        val out = TinyRemapStepType().create(RemapStepSpec(), ctx)
            .apply(inputJar(), ctx)

        JarFile(out.toFile()).use { jar ->
            assertNotNull(jar.getEntry("b/B.class"))
            assertNull(jar.getEntry("a/A.class"))
            val bytes = jar.getInputStream(jar.getEntry("b/B.class")).readBytes()
            assertTrue(bytes.containsAscii("b/B"))
            assertTrue(!bytes.containsAscii("a/A"))
        }
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }

}
