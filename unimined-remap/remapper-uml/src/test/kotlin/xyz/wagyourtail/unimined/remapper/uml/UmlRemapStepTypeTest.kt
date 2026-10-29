package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
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

    private val mappings: MappingData = UmlMappingData(tree)

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

    /**
     * Classpath jar: `a/Base` with `private void m()`. Input jar:
     * `a/Sub extends a/Base` redefining `m()`. A declared access rule
     * widening `a/Base.m` lets analysis see the widened access so member
     * propagation recognizes `Sub.m` as an override and renames it.
     */
    private fun propagationJars(): Pair<Path, Path> {
        val srcDir = dir.resolve("src/a")
        Files.createDirectories(srcDir)
        val base = srcDir.resolve("Base.java")
        val sub = srcDir.resolve("Sub.java")
        Files.writeString(
            base, "package a; public class Base { private void m() {} }"
        )
        Files.writeString(
            sub, "package a; public class Sub extends Base { public void m() {} }"
        )
        val baseClasses = dir.resolve("base-classes")
        val subClasses = dir.resolve("sub-classes")
        Files.createDirectories(baseClasses)
        Files.createDirectories(subClasses)
        assertEquals(
            0, ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", baseClasses.toString(), base.toString())
        )
        assertEquals(
            0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-cp", baseClasses.toString(),
                "-d", subClasses.toString(), sub.toString()
            )
        )
        fun jar(path: Path, vararg entries: Pair<String, Path>): Path {
            JarOutputStream(Files.newOutputStream(path)).use { out ->
                for ((name, file) in entries) {
                    out.putNextEntry(JarEntry(name))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
            return path
        }
        return jar(dir.resolve("base.jar"), "a/Base.class" to baseClasses.resolve("a/Base.class")) to
                jar(dir.resolve("sub.jar"), "a/Sub.class" to subClasses.resolve("a/Sub.class"))
    }

    private fun methodNames(jarPath: Path, entry: String): List<String> {
        val names = mutableListOf<String>()
        JarFile(jarPath.toFile()).use { jar ->
            val bytes = jar.getInputStream(jar.getEntry(entry)).readBytes()
            org.objectweb.asm.ClassReader(bytes).accept(
                object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int, name: String, descriptor: String,
                        signature: String?, exceptions: Array<out String>?
                    ): org.objectweb.asm.MethodVisitor? {
                        names += name
                        return null
                    }
                }, 0
            )
        }
        return names
    }

    @Test
    fun `access model overlay propagates widened member mappings`() {
        val (baseJar, subJar) = propagationJars()
        val propagationTree = runBlocking {
            TinyV2Reader.read(
                """
                tiny	2	0	official	named
                c	a/Base	b/Base
                	m	()V	m	renamed
                """.trimIndent()
            )
        }
        val mappings: MappingData = UmlMappingData(propagationTree)
        val model = xyz.wagyourtail.unimined.api.access.AccessModel(
            listOf(
                xyz.wagyourtail.unimined.api.access.AccessModel.Section(
                    "official",
                    listOf(
                        xyz.wagyourtail.unimined.api.access.AccessModel.Entry(
                            xyz.wagyourtail.unimined.api.access.AccessModel.Kind.METHOD,
                            "a/Base", "m", "()V",
                            xyz.wagyourtail.unimined.api.access.AccessModel.Change(0x0001, 7)
                        )
                    )
                )
            )
        )
        val ctx = object : StepContext {
            override val spec = this@UmlRemapStepTypeTest.spec
            override val mappings = mappings
            override val classpath = listOf(baseJar)
            override val accessModel = model
            override val workDir = dir
            override val logger: Logger = Logging.getLogger(UmlRemapStepTypeTest::class.java)
        }
        val out = UmlRemapStepType().create(RemapStepSpec(), ctx).apply(subJar, ctx)
        assertTrue("renamed" in methodNames(out, "a/Sub.class"))
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }

}
