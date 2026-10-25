package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.GradleException
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class AccessRemapStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val spec = object : RemapSpec {
        override val sourceNamespace = "intermediary"
        override val targetNamespace = "named"
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    private val tree = object : MappingTree {
        override val namespaces = listOf("intermediary", "named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
            visitor.visitClass("example/Target", "mapped/Target")
            visitor.visitClass("example/Marker", "mapped/Marker")
            visitor.visitMethod("example/Target", "run", "()V", "execute")
            visitor.visitField("example/Target", "value", "I", "count")
        }
    }

    private val mappings = object : MappingData {
        override val viewTypes = setOf(MappingTree::class)

        @Suppress("UNCHECKED_CAST")
        override fun <T : MappingDataView> view(type: Class<T>): T? =
            if (type == MappingTree::class.java) tree as T else null
    }

    private fun ctx(): StepContext = object : StepContext {
        override val spec = this@AccessRemapStepTypeTest.spec
        override val mappings = this@AccessRemapStepTypeTest.mappings
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = Logging.getLogger(AccessRemapStepTypeTest::class.java)
    }

    private fun inputJar(): Path {
        val jar = dir.resolve("in.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("test.accesswidener", """
                accessWidener v1 intermediary
                accessible class example/Target
                accessible method example/Target run ()V
                accessible field example/Target value I
            """.trimIndent())
            put("META-INF/accesstransformer.cfg", """
                public example.Target
                protected example.Target run()V
            """.trimIndent())
            put("custom.ct", """
                classTweaker v2 intermediary
                accessible class example/Target
                mutable field example/Target value I
                inject-interface example/Target example/Marker<TT;>
            """.trimIndent())
            put("resource.txt", "unchanged")
        }
        return jar
    }

    @Test
    fun `registry discovers access remapper via ServiceLoader`() {
        val type = RemapStepRegistry().resolve(
            AccessRemapStepSpec(), RemapSurface.JAR, spec
        )
        assertEquals("access", type.name)
        assertEquals("remap-access", type.stepName)
    }

    @Test
    fun `rewrites embedded AW AT and CT resources`() {
        val ctx = ctx()
        val out = AccessRemapStepType().create(AccessRemapStepSpec(), ctx)
            .apply(inputJar(), ctx)

        JarFile(out.toFile()).use { jar ->
            val aw = jar.entryText("test.accesswidener")
            assertTrue(aw.contains("accessWidener v1 named"))
            assertTrue(aw.contains("accessible class mapped/Target"))
            assertTrue(aw.contains("accessible method mapped/Target execute ()V"))
            assertTrue(aw.contains("accessible field mapped/Target count I"))

            val at = jar.entryText("META-INF/accesstransformer.cfg")
            assertTrue(at.contains("public mapped/Target"))
            assertTrue(at.contains("protected mapped/Target execute()V"))

            val ct = jar.entryText("custom.ct")
            assertTrue(ct.contains("classTweaker\tv2\tnamed"))
            assertTrue(ct.contains("accessible class mapped/Target"))
            assertTrue(ct.contains("mutable field mapped/Target count I"))
            assertTrue(ct.contains("inject-interface mapped/Target mapped/Marker<TT;>"))
            assertEquals("unchanged", jar.entryText("resource.txt"))
        }
    }

    @Test
    fun `explicit paths and format filter control processing`() {
        val jar = dir.resolve("explicit.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("loader-declared.txt", "public example.Target\n")
            put("test.accesswidener", """
                accessWidener v1 intermediary
                accessible class example/Target
            """.trimIndent())
        }

        val stepSpec = AccessRemapStepSpec().apply {
            formats = setOf("at")
            paths = setOf("loader-declared.txt")
        }
        val ctx = ctx()
        val out = AccessRemapStepType().create(stepSpec, ctx).apply(jar, ctx)

        JarFile(out.toFile()).use { result ->
            assertEquals("public mapped/Target\n", result.entryText("loader-declared.txt"))
            assertTrue(result.entryText("test.accesswidener").contains("example/Target"))
        }
    }

    @Test
    fun `unknown format fails clearly`() {
        val spec = AccessRemapStepSpec().apply { formats = setOf("unknown") }
        val e = assertThrows(GradleException::class.java) {
            AccessRemapStepType().create(spec, ctx()).apply(inputJar(), ctx())
        }
        assertTrue(e.message!!.contains("unknown"))
    }

    private fun JarFile.entryText(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().use { it.readText() }

}
