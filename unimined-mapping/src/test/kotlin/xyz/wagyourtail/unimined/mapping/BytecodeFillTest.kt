package xyz.wagyourtail.unimined.mapping

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.commons.core.logger.NullLogger
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMappingContext
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import xyz.wagyourtail.unimined.mapping.processors.BytecodeFillPostProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * [BytecodeFillPostProcessor] — the ploceus `MappingFiller` port — over a
 * tiny ASM-built jar: mapped method names propagate down the scanned
 * hierarchy onto overriding declarations the tree leaves unmapped,
 * across classes that do not redeclare the method, and onto
 * tree-absent classes via verbatim cells. Named-namespace
 * bridge/specialized unification and the library-hierarchy boundary
 * are covered as parity checks.
 */
class BytecodeFillTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = RuntimeMappingContext(
        "test-env",
        NullLogger.INSTANCE,
        null,
        null,
        mavenResolver = {
            throw UnsupportedOperationException("no maven resolution in tests")
        }
    )

    private val official = Namespace("official")
    private val intermediary = Namespace("intermediary")
    private val named = Namespace("named")

    // ---- fixtures ----

    private data class MethodSpec(
        val access: Int,
        val name: String,
        val desc: String,
        val emit: MethodVisitor.() -> Unit
    )

    private fun voidMethod(
        name: String, desc: String = "()V", access: Int = Opcodes.ACC_PUBLIC
    ) = MethodSpec(access, name, desc) { visitInsn(Opcodes.RETURN) }

    /** The javac bridge shape — load args, checkcast the receiver
     *  argument, one self-invocation at the narrower descriptor, return. */
    private fun bridgeMethod(
        name: String, desc: String, targetName: String,
        targetDesc: String, owner: String
    ) = MethodSpec(Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE, name, desc) {
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ALOAD, 1)
        visitTypeInsn(Opcodes.CHECKCAST, owner)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetName, targetDesc, false)
        visitInsn(Opcodes.IRETURN)
    }

    private fun classBytes(
        name: String,
        superName: String = "java/lang/Object",
        interfaces: Array<String> = emptyArray(),
        vararg methods: MethodSpec
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
        for (m in methods) {
            val mv = cw.visitMethod(m.access, m.name, m.desc, null, null)
            mv.visitCode()
            m.emit(mv)
            mv.visitMaxs(-1, -1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(path: Path, vararg classes: Pair<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jos ->
            for ((name, bytes) in classes.sortedBy { it.first }) {
                jos.putNextEntry(JarEntry("$name.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    /**
     * The scanned product jar: an `a/Base -> a/Sub -> a/Deep -> a/Deep2`
     * chain redeclaring `m(I)V` at the ends only, an unrelated
     * `a/Other` redeclaring `m`, a tree-absent `a/Unmapped` subclass,
     * an `a/Cmp implements Comparable` bridge pair, and `a/SubLib`
     * extending a library-jar `lib/LibBase`.
     */
    private fun fixtureJar(): Path = dir.resolve("mc.jar").also { jar ->
        writeJar(
            jar,
            "a/Base" to classBytes("a/Base", methods = arrayOf(voidMethod("m", "(I)V"))),
            "a/Sub" to classBytes(
                "a/Sub", superName = "a/Base",
                methods = arrayOf(voidMethod("m", "(I)V"))
            ),
            // no `m` redeclaration — propagation must cross it
            "a/Deep" to classBytes("a/Deep", superName = "a/Sub"),
            "a/Deep2" to classBytes(
                "a/Deep2", superName = "a/Deep",
                methods = arrayOf(voidMethod("m", "(I)V"))
            ),
            "a/Other" to classBytes("a/Other", methods = arrayOf(voidMethod("m", "(I)V"))),
            "a/Unmapped" to classBytes(
                "a/Unmapped", superName = "a/Base",
                methods = arrayOf(voidMethod("m", "(I)V"))
            ),
            "a/SubLib" to classBytes(
                "a/SubLib", superName = "lib/LibBase",
                methods = arrayOf(voidMethod("l"))
            ),
            // scanned supertype declaring the bridge signature — the
            // verifier needs `compareTo(Ljava/lang/Object;)I` in an
            // already-indexed supertype's method set; lazily-read JDK
            // supertypes land their methods too late to count
            // (ploceus ordering parity)
            "a/GenBase" to classBytes(
                "a/GenBase", interfaces = arrayOf("java/lang/Comparable"),
                methods = arrayOf(
                    MethodSpec(Opcodes.ACC_PUBLIC, "compareTo", "(Ljava/lang/Object;)I") {
                        visitInsn(Opcodes.ICONST_0)
                        visitInsn(Opcodes.IRETURN)
                    }
                )
            ),
            "a/Cmp" to classBytes(
                "a/Cmp", superName = "a/GenBase",
                methods = arrayOf(
                    MethodSpec(Opcodes.ACC_PUBLIC, "a", "(La/Cmp;)I") {
                        visitInsn(Opcodes.ICONST_0)
                        visitInsn(Opcodes.IRETURN)
                    },
                    bridgeMethod(
                        "compareTo", "(Ljava/lang/Object;)I",
                        "a", "(La/Cmp;)I", "a/Cmp"
                    )
                )
            )
        )
    }

    private fun libraryJar(): Path = dir.resolve("lib.jar").also { jar ->
        writeJar(jar, "lib/LibBase" to classBytes("lib/LibBase", methods = arrayOf(voidMethod("l"))))
    }

    /** Tree matching the jar — `a/Base.m` mapped + param; subclasses
     *  unmapped; `a/Cmp`'s bridge mapped, its specialized `a` absent. */
    private fun fixtureTree(): UmlMappingData = UmlMappingData(
        mappingTree("official", "intermediary", "named") {
            clazz("a/Base", "i/Base", "b/Base") {
                method("m;(I)V", "m_1", "run") {
                    parameter(1, 1, null, null, "arg0")
                }
            }
            clazz("a/Sub", "i/Sub", "b/Sub") {
                method("m;(I)V")
            }
            clazz("a/Other", "i/Other", "b/Other") {
                method("m;(I)V")
            }
            clazz("a/SubLib", "i/SubLib", "b/SubLib") {
                method("l;()V")
            }
            clazz("a/GenBase", "i/GenBase", "b/GenBase")
            clazz("a/Cmp", "i/Cmp", "b/Cmp") {
                method("compareTo;(Ljava/lang/Object;)I", "m_2", "compareTo")
            }
            // mapped library supertype — may seed lookups, never rows
            clazz("lib/LibBase", "lib/L", "lib/LibBase") {
                method("l;()V", "l_1", "libRun")
            }
        },
        mapOf("namedNamespaces" to "named")
    )

    private fun filled(): MappingTree {
        val data = fixtureTree()
        BytecodeFillPostProcessor(
            jars = mapOf("official" to { listOf(fixtureJar()) }),
            libraries = { listOf(libraryJar()) }
        ).process(data, ctx)
        return data.view<MappingTree>()!!
    }

    private fun MappingTree.method(
        cls: String, name: String, desc: String
    ) = getClass(official, InternalName.unchecked(cls))?.let {
        with(this) {
            it.getMethod(official, UnqualifiedName.unchecked(name), MethodDescriptor.unchecked(desc))
        }
    }

    // ---- tests ----

    @Test
    fun `propagates mapped method and parameter names to overriding declarations`() {
        val tree = filled()
        val m = tree.method("a/Sub", "m", "(I)V")!!
        assertEquals("m_1", m.names[intermediary]?.value)
        assertEquals("run", m.names[named]?.value)
        assertEquals(
            "arg0",
            m.parameters.first { it.lvOrd == 1 }.names[named]?.value
        )
    }

    @Test
    fun `propagation crosses classes that do not redeclare the method`() {
        val tree = filled()
        // a/Deep never redeclared m — no row is synthesized for it
        assertNull(tree.getClass(official, InternalName.unchecked("a/Deep")))
        // a/Deep2 redeclares — filled through the skipped ancestor
        val m = tree.method("a/Deep2", "m", "(I)V")!!
        assertEquals("m_1", m.names[intermediary]?.value)
        assertEquals("run", m.names[named]?.value)
    }

    @Test
    fun `unrelated classes sharing the signature stay unfilled`() {
        val tree = filled()
        val m = tree.method("a/Other", "m", "(I)V")!!
        assertNull(m.names[intermediary])
        assertNull(m.names[named])
    }

    @Test
    fun `tree-absent classes get verbatim cells plus filled members`() {
        val tree = filled()
        val cls = tree.getClass(official, InternalName.unchecked("a/Unmapped"))!!
        // member-groups semantics — the synthesized class row carries
        // its source cell only; unmapped class names resolve to
        // themselves in every namespace, so target-column class cells
        // stay absent rather than landing verbatim in the first
        // column (ploceus's write-down artifact)
        assertEquals("a/Unmapped", cls.names[official]?.value)
        assertNull(cls.names[intermediary])
        assertNull(cls.names[named])
        val m = tree.method("a/Unmapped", "m", "(I)V")!!
        assertEquals("m_1", m.names[intermediary]?.value)
        assertEquals("run", m.names[named]?.value)
    }

    @Test
    fun `named namespace unifies bridge and specialized methods`() {
        val tree = filled()
        val specialized = tree.method("a/Cmp", "a", "(La/Cmp;)I")!!
        // the specialized method takes the bridge's named name…
        assertEquals("compareTo", specialized.names[named]?.value)
        // …but keeps its own intermediary identity — only named-role
        // namespaces unify
        assertNull(specialized.names[intermediary])
    }

    @Test
    fun `library classes contribute hierarchy but never gain rows`() {
        val tree = filled()
        // a/SubLib overrides lib/LibBase.l — ploceus's jarClasses gate:
        // library seeds never propagate, so the override stays unmapped
        // member-groups semantics — the mapped library supertype's
        // group includes the product override, so the override gains
        // the library's names: consistent with what `uml:remap` writes
        // at jar time (ploceus's jarClasses gate missed these)
        val m = tree.method("a/SubLib", "l", "()V")!!
        assertEquals("l_1", m.names[intermediary]?.value)
        assertEquals("libRun", m.names[named]?.value)
        // the mapped library row itself is untouched
        val lib = tree.method("lib/LibBase", "l", "()V")!!
        assertEquals("l_1", lib.names[intermediary]?.value)
        assertEquals("libRun", lib.names[named]?.value)
    }

    @Test
    fun `absent jar is a no-op`() {
        val data = fixtureTree()
        BytecodeFillPostProcessor(
            jars = mapOf("official" to { listOf(dir.resolve("missing.jar")) })
        ).process(data, ctx)
        val tree = data.view<MappingTree>()!!
        val m = tree.method("a/Sub", "m", "(I)V")!!
        assertNull(m.names[named])
        assertNull(tree.getClass(official, InternalName.unchecked("a/Unmapped")))
    }

    @Test
    fun `explicit targets restrict fill namespaces`() {
        val data = fixtureTree()
        BytecodeFillPostProcessor(
            jars = mapOf("official" to { listOf(fixtureJar()) }),
            libraries = { listOf(libraryJar()) },
            targets = setOf("named")
        ).process(data, ctx)
        val tree = data.view<MappingTree>()!!
        val m = tree.method("a/Sub", "m", "(I)V")!!
        assertNull(m.names[intermediary])
        assertEquals("run", m.names[named]?.value)
    }

}
