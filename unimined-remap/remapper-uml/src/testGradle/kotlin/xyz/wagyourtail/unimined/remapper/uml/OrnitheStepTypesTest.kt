package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.*

class OrnitheStepTypesTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects

    private val spec = object : RemapSpec {
        override val sourceNamespace = "feather"
        override val targetNamespace = "feather"
        override val classpath: List<Path> = emptyList()
    }

    private fun ctx(): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState(spec.sourceNamespace, null)
        }
        override val spec = this@OrnitheStepTypesTest.spec
        override val mappings = null
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = uniminedLogger(
            org.gradle.api.logging.Logging.getLogger(OrnitheStepTypesTest::class.java)
        )
    }

    /** Compile the given java sources (no debug info) into a jar. */
    private fun inputJar(vararg sources: Pair<String, String>): Path {
        val srcDir = dir.resolve("src")
        Files.createDirectories(srcDir)
        val files = sources.map { (name, content) ->
            srcDir.resolve("${name.substringAfterLast('/')}.java")
                .also { Files.writeString(it, content) }
        }
        val classes = dir.resolve("classes")
        Files.createDirectories(classes)
        val rc = ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g:none", "-d", classes.toString(),
            *files.map { it.toString() }.toTypedArray()
        )
        assertEquals(0, rc)
        return jarOf(classes)
    }

    private fun jarOf(classesDir: Path, extra: Map<String, ByteArray> = emptyMap()): Path {
        val jar = dir.resolve("in-${System.nanoTime()}.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classesDir).use { walk ->
                walk.filter { it.toString().endsWith(".class") }.forEach { cls ->
                    out.putNextEntry(JarEntry(classesDir.relativize(cls).toString()))
                    Files.copy(cls, out)
                    out.closeEntry()
                }
            }
            for ((name, bytes) in extra) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun readClass(jar: Path, name: String): ClassNode {
        JarFile(jar.toFile()).use { jf ->
            val entry = jf.getEntry("$name.class")
            assertNotNull(entry, "$name missing from $jar")
            val node = ClassNode()
            ClassReader(jf.getInputStream(entry)).accept(node, 0)
            return node
        }
    }

    private fun dataFile(name: String, content: String): Path =
        dir.resolve(name).also { Files.writeString(it, content) }

    @Test
    fun `registry discovers ornithe steps via ServiceLoader`() {
        val registry = RemapStepRegistry()
        assertEquals("nester", registry.resolve(
            objects.newInstance(NesterStepSpec::class.java), RemapSurface.JAR, spec
        ).stepName)
        assertEquals("condor", registry.resolve(
            objects.newInstance(CondorStepSpec::class.java), RemapSurface.JAR, spec
        ).stepName)
        assertEquals("preen", registry.resolve(
            objects.newInstance(PreenStepSpec::class.java), RemapSurface.JAR, spec
        ).stepName)
    }

    @Test
    fun `condor generates local variable tables`() {
        val jar = inputJar(
            "a/A" to """
                package a;
                public class A {
                    public int m(int x) {
                        int y = x + 1;
                        for (int i = 0; i < 3; i++) y += i;
                        return y;
                    }
                }
            """.trimIndent()
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(CondorStepSpec::class.java)
        val out = CondorStepType().create(stepSpec, ctx).apply(jar, ctx)
        val method = readClass(out, "a/A").methods.first { it.name == "m" }
        assertTrue(
            method.localVariables.orEmpty().size >= 2,
            "expected generated LVT entries, got ${method.localVariables}"
        )
        assertTrue(method.localVariables.any { it.name == "var1" })
    }

    @Test
    fun `preen splits merged bridge methods`() {
        // C declares m(String); D's call site invokes m(Object) — the
        // ProGuard-merged shape. A real jar would carry the bridge.
        val c = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, "m", "(Ljava/lang/String;)V",
                null, null
            ).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
            })
        }
        val d = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "D", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, "call", "()V", null, null
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "C")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "C", "<init>", "()V", false)
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "C", "m", "(Ljava/lang/Object;)V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(3, 1)
            })
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
            })
        }
        // C also needs an <init> for D's call site
        c.methods.add(MethodNode(
            Opcodes.ASM9, Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
        })
        val empty = dir.resolve("empty").also { Files.createDirectories(it) }
        val jar = jarOf(
            empty,
            mapOf("C.class" to c.toBytes(), "D.class" to d.toBytes())
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(PreenStepSpec::class.java)
        val out = PreenStepType().create(stepSpec, ctx).apply(jar, ctx)
        val cOut = readClass(out, "C")
        val bridge = cOut.methods.firstOrNull {
            it.name == "m" && it.desc == "(Ljava/lang/Object;)V"
        } ?: fail("expected synthesized bridge m(Object) on C")
        assertTrue(bridge.access and Opcodes.ACC_BRIDGE != 0)
    }

    @Test
    fun `nester applies inner-class attributes`() {
        // class file with no InnerClasses attribute, restored by .nest
        val inner = ClassNode().apply {
            visit(
                Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "a/Outer\$Inner", null, "java/lang/Object", null
            )
        }
        val outer = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/Outer", null, "java/lang/Object", null)
        }
        val empty = dir.resolve("empty2").also { Files.createDirectories(it) }
        val jar = jarOf(
            empty,
            mapOf("a/Outer.class" to outer.toBytes(), "a/Outer\$Inner.class" to inner.toBytes())
        )
        val nests = dataFile(
            "mappings.nest",
            "a/Outer\$Inner\ta/Outer\t\t\tInner\t9\n"
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(NesterStepSpec::class.java)
        stepSpec.dataFiles.from(nests.toFile())
        val out = NesterStepType().create(stepSpec, ctx).apply(jar, ctx)
        val inners = readClass(out, "a/Outer\$Inner").innerClasses
        assertTrue(
            inners.any { it.name == "a/Outer\$Inner" && it.outerName == "a/Outer" },
            "expected InnerClasses entry for a/Outer\$Inner, got $inners"
        )
    }

    @Test
    fun `sparrow applies signature attributes`() {
        val cls = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null)
        }
        val empty = dir.resolve("empty3").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("a/A.class" to cls.toBytes()))
        val sigs = dataFile(
            "mappings.sigs",
            "a/A\t<T:Ljava/lang/Object;>Ljava/lang/Object;\n"
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(SparrowStepSpec::class.java)
        stepSpec.dataFiles.from(sigs.toFile())
        val out = SparrowStepType().create(stepSpec, ctx).apply(jar, ctx)
        assertEquals(
            "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            readClass(out, "a/A").signature
        )
    }

    @Test
    fun `raven applies exceptions attributes`() {
        val cls = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, "m", "()V", null, null
            ).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
            })
        }
        val empty = dir.resolve("empty4").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("a/A.class" to cls.toBytes()))
        val excs = dataFile(
            "mappings.excs",
            "a/A\n\tm\t()V\tjava/lang/Exception\n"
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(RavenStepSpec::class.java)
        stepSpec.dataFiles.from(excs.toFile())
        val out = RavenStepType().create(stepSpec, ctx).apply(jar, ctx)
        val method = readClass(out, "a/A").methods.first { it.name == "m" }
        assertTrue(
            method.exceptions.contains("java/lang/Exception"),
            "expected Exceptions attribute, got ${method.exceptions}"
        )
    }

    private fun ClassNode.toBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        accept(writer)
        return writer.toByteArray()
    }
}
