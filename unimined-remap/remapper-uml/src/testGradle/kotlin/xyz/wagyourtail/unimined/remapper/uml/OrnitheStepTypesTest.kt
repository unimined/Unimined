package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
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
    private fun inputJar(
        vararg sources: Pair<String, String>,
        extraArgs: List<String> = emptyList()
    ): Path {
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
            *(extraArgs + files.map { it.toString() }).toTypedArray()
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
        // upstream contract: slot 0 is `this`, everything else gets
        // type-derived names — i/j/k… counting up per primitive type
        val vars = method.localVariables.orEmpty()
        assertTrue(vars.size >= 4, "expected generated LVT entries, got $vars")
        val byIndex = vars.associateBy { it.index }
        assertEquals("this", byIndex[0]?.name)
        assertEquals("La/A;", byIndex[0]?.desc)
        assertEquals("i", byIndex[1]?.name)
        assertEquals("I", byIndex[1]?.desc)
        assertEquals("I", byIndex[2]?.desc)
        assertTrue(
            readClass(jar, "a/A").methods.first { it.name == "m" }
                .localVariables.orEmpty().isEmpty(),
            "the input artifact must not be mutated in place"
        )
    }

    @Test
    fun `condor regenerates incomplete tables`() {
        // A partial table — covers the parameter slot but has no `this`
        // entry — fails upstream's isComplete() and is regenerated
        // wholesale rather than kept.
        val c = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "PC", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, "m", "(I)I", null, null
            ).apply {
                visitCode()
                val start = org.objectweb.asm.Label()
                val end = org.objectweb.asm.Label()
                visitLabel(start)
                visitVarInsn(Opcodes.ILOAD, 1)
                visitInsn(Opcodes.IRETURN)
                visitLabel(end)
                // partial: the param only — no entry at index 0
                visitLocalVariable("x", "I", null, start, end, 1)
                visitMaxs(1, 2)
                visitEnd()
            })
        }
        val empty = dir.resolve("empty-condor-partial").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("PC.class" to c.toBytes()))

        val ctx = ctx()
        val stepSpec = objects.newInstance(CondorStepSpec::class.java)
        val out = CondorStepType().create(stepSpec, ctx).apply(jar, ctx)
        val vars = readClass(out, "PC").methods.first { it.name == "m" }
            .localVariables.orEmpty()
        val byIndex = vars.associateBy { it.index }
        assertEquals(
            "this", byIndex[0]?.name,
            "incomplete tables must be regenerated — slot 0 gains `this`, got $vars"
        )
        assertEquals("LPC;", byIndex[0]?.desc)
        assertNull(
            vars.firstOrNull { it.name == "x" },
            "regeneration discards the partial table's original entries"
        )
    }

    @Test
    fun `condor strips generated entries that reuse parameter slots`() {
        // The ProGuard shape removeInvalidEntries() exists for: bytecode
        // reuses a parameter slot for a different-typed local mid-method.
        // Frame analysis yields two entries for the slot; the second —
        // reusing the param index — is stripped.
        val c = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "RU", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/String;)V", null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.ICONST_0)
                // slot 0 reused as an int local after the param is dead
                visitVarInsn(Opcodes.ISTORE, 0)
                visitVarInsn(Opcodes.ILOAD, 0)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            })
        }
        val empty = dir.resolve("empty-condor-reuse").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("RU.class" to c.toBytes()))

        val ctx = ctx()
        val stepSpec = objects.newInstance(CondorStepSpec::class.java)
        val out = CondorStepType().create(stepSpec, ctx).apply(jar, ctx)
        val slotZero = readClass(out, "RU").methods.first { it.name == "m" }
            .localVariables.orEmpty().filter { it.index == 0 }
        assertEquals(
            1, slotZero.size,
            "entries reusing the parameter slot must be stripped, got $slotZero"
        )
        assertEquals("Ljava/lang/String;", slotZero.single().desc)
    }

    @Test
    fun `condor keeps parameter names on generated entries`() {
        // keepParameterNames: with a MethodParameters attribute present,
        // generated entries at parameter slots take the declared names
        // instead of the type-derived ones.
        val jar = inputJar(
            "a/B" to """
                package a;
                public class B {
                    public int m(int alpha, long beta) {
                        return alpha + (int) beta;
                    }
                }
            """.trimIndent(),
            extraArgs = listOf("-parameters")
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(CondorStepSpec::class.java)
        val out = CondorStepType().create(stepSpec, ctx).apply(jar, ctx)
        val method = readClass(out, "a/B").methods.first { it.name == "m" }
        val byIndex = method.localVariables.orEmpty().associateBy { it.index }
        assertEquals("alpha", byIndex[1]?.name)
        assertEquals("beta", byIndex[2]?.name)
    }

    @Test
    fun `preen clears merged bridge access without synthesizing methods`() {
        // The ProGuard-merged shape upstream preen recognizes: an existing
        // ACC_BRIDGE method whose bridge-forwarding prefix is followed by
        // specialized implementation bytecode. Ploceus only normalizes that
        // method's access; it must not recreate the specialized method.
        val c = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or
                        Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE,
                "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I",
                null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                visitVarInsn(Opcodes.ALOAD, 2)
                visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "C", "compare",
                    "(Ljava/lang/String;Ljava/lang/String;)I", false
                )
                visitInsn(Opcodes.IRETURN)
                // specialized implementation body appended by ProGuard
                visitInsn(Opcodes.ICONST_0)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(3, 3)
                visitEnd()
            })
        }
        val empty = dir.resolve("empty-preen-access").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("C.class" to c.toBytes()))
        val inputAccess = readClass(jar, "C").methods.single { it.name == "compare" }.access

        val ctx = ctx()
        val stepSpec = objects.newInstance(PreenStepSpec::class.java)
        val out = PreenStepType().create(stepSpec, ctx).apply(jar, ctx)
        val methods = readClass(out, "C").methods.filter { it.name == "compare" }
        assertEquals(
            1, methods.size,
            "preen must rewrite access on the existing method only, never synthesize another descriptor"
        )
        val merged = methods.single()
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)I", merged.desc)
        assertEquals(
            0,
            merged.access and (Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE),
            "merged bridge access bits must be cleared"
        )
        assertTrue(merged.access and Opcodes.ACC_PUBLIC != 0)
        assertEquals(
            inputAccess,
            readClass(jar, "C").methods.single { it.name == "compare" }.access,
            "the input artifact must not be mutated in place"
        )
    }

    @Test
    fun `preen leaves ordinary bridge methods untouched`() {
        // A normal javac bridge ends after forwarding to the specialized
        // method. It is not a ProGuard merge artifact, so upstream preen
        // leaves its access bits alone.
        val c = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "CB", null, "java/lang/Object", null)
            methods.add(MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or
                        Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE,
                "m", "(Ljava/lang/Object;)V", null, null
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "CB", "m",
                    "(Ljava/lang/String;)V", false
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            })
        }
        val empty = dir.resolve("empty-preen-ordinary").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("CB.class" to c.toBytes()))
        val before = readClass(jar, "CB").methods.single { it.name == "m" }.access

        val ctx = ctx()
        val stepSpec = objects.newInstance(PreenStepSpec::class.java)
        val out = PreenStepType().create(stepSpec, ctx).apply(jar, ctx)
        val after = readClass(out, "CB").methods.single { it.name == "m" }
        assertEquals(before, after.access)
        assertTrue(after.access and Opcodes.ACC_BRIDGE != 0)
        assertTrue(after.access and Opcodes.ACC_SYNTHETIC != 0)
    }

    @Test
    fun `preen does not bridge a call resolved on a superclass`() {
        fun voidMethod(name: String, desc: String, body: MethodNode.() -> Unit = {}) =
            MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, name, desc, null, null).apply {
                visitCode()
                body()
                visitInsn(Opcodes.RETURN)
                visitMaxs(3, 1)
                visitEnd()
            }
        val parent = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "P", null, "java/lang/Object", null)
            methods.add(voidMethod("m", "(FF)V"))
        }
        val child = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "C", null, "P", null)
            methods.add(voidMethod("m", "(IZ)V") {
                // the SlimeEntity shape: a local same-name overload calls the
                // inherited different-descriptor method through the child owner
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.FCONST_0)
                visitInsn(Opcodes.FCONST_1)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "C", "m", "(FF)V", false)
            })
        }
        val empty = dir.resolve("empty-preen-inherited").also { Files.createDirectories(it) }
        val jar = jarOf(
            empty,
            mapOf("P.class" to parent.toBytes(), "C.class" to child.toBytes())
        )
        val ctx = ctx()
        val stepSpec = objects.newInstance(PreenStepSpec::class.java)
        val out = PreenStepType().create(stepSpec, ctx).apply(jar, ctx)
        val cOut = readClass(out, "C")
        assertNull(
            cOut.methods.firstOrNull { it.name == "m" && it.desc == "(FF)V" },
            "the inherited exact method already satisfies the call; no child bridge may be synthesized"
        )
        val invoke = cOut.methods.single { it.desc == "(IZ)V" }.instructions.toArray()
            .filterIsInstance<MethodInsnNode>().single { it.name == "m" }
        assertEquals("(FF)V", invoke.desc)
    }

    @Test
    fun `preen does not synthesize for primitive-incompatible overload calls`() {
        val child = ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "PC", null, "java/lang/Object", null)
            methods.add(MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "m", "(IZ)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.FCONST_0)
                visitInsn(Opcodes.FCONST_1)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "PC", "m", "(FF)V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(3, 1)
                visitEnd()
            })
        }
        val empty = dir.resolve("empty-preen-primitive").also { Files.createDirectories(it) }
        val jar = jarOf(empty, mapOf("PC.class" to child.toBytes()))
        val ctx = ctx()
        val stepSpec = objects.newInstance(PreenStepSpec::class.java)
        val out = PreenStepType().create(stepSpec, ctx).apply(jar, ctx)
        assertNull(
            readClass(out, "PC").methods.firstOrNull { it.name == "m" && it.desc == "(FF)V" },
            "int/float overloads are distinct methods, never bridge-compatible"
        )
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
