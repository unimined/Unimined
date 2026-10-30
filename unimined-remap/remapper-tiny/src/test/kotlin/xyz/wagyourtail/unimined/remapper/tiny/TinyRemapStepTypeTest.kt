package xyz.wagyourtail.unimined.remapper.tiny

import xyz.wagyourtail.unimined.remap.spi.PipelineState
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.mappingTree
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
    private val tree = mappingTree("official", "named") {
        clazz("a/A", "b/B") {
            field("f;I", "g")
            method("m;()V", "n")
        }
    }

    private val mappings: MappingData = UmlMappingData(tree)

    private fun ctx(
        classpath: List<Path> = emptyList(),
        accessModel: AccessModel? = null
    ): StepContext = object : StepContext {
     override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
        override val spec = this@TinyRemapStepTypeTest.spec
        override val mappings = this@TinyRemapStepTypeTest.mappings
        override val classpath = classpath
        override val accessModel = accessModel
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

    /**
     * Classpath jar: `a/Base` with `private void m()`. Input jar:
     * `a/Sub extends a/Base` redefining `m()` — compilable because the
     * super member is private. When a declared access rule widens
     * `a/Base.m`, analysis must see the widened access so member
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

    private val propagationTree = mappingTree("official", "named") {
        clazz("a/Base", "b/Base") {
            method("m;()V", "renamed")
        }
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

    private fun propagationCtx(baseJar: Path, model: AccessModel?): StepContext {
        val mappings: MappingData = UmlMappingData(propagationTree)
        return object : StepContext {
     override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
            override val spec = this@TinyRemapStepTypeTest.spec
            override val mappings = mappings
            override val classpath = listOf(baseJar)
            override val accessModel = model
            override val workDir = dir
            override val logger: Logger = Logging.getLogger(TinyRemapStepTypeTest::class.java)
        }
    }

    @Test
    fun `access model overlay propagates widened member mappings`() {
        val (baseJar, subJar) = propagationJars()
        val model = AccessModel(
            listOf(
                AccessModel.Section(
                    "official",
                    listOf(
                        AccessModel.Entry(
                            AccessModel.Kind.METHOD, "a/Base", "m", "()V",
                            AccessModel.Change(0x0001, 7) // private -> public
                        )
                    )
                )
            )
        )
        val ctx = propagationCtx(baseJar, model)
        val out = TinyRemapStepType().create(RemapStepSpec(), ctx).apply(subJar, ctx)
        assertTrue("renamed" in methodNames(out, "a/Sub.class"))
    }

    @Test
    fun `without the overlay propagation misses the private super member`() {
        val (baseJar, subJar) = propagationJars()
        val ctx = propagationCtx(baseJar, null)
        val out = TinyRemapStepType().create(RemapStepSpec(), ctx).apply(subJar, ctx)
        assertTrue("m" in methodNames(out, "a/Sub.class"))
        assertTrue("renamed" !in methodNames(out, "a/Sub.class"))
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }

}
