package xyz.wagyourtail.unimined.minecraft.merge

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * The dispatch merger unions equal members like STRICT and splices
 * divergent same-desc method bodies inline behind a side-property
 * check — no renamed side copies, so `<init>`/`<clinit>` merge too.
 * Fails loud on the documented limits (abstract/native, class
 * metadata).
 */
class DispatchMergerTest {

    private fun method(name: String, desc: String, access: Int,
                       vararg insns: org.objectweb.asm.tree.AbstractInsnNode
    ) = MethodNode(access, name, desc, null, null).apply {
        insns.forEach { instructions.add(it) }
    }

    private fun cls(name: String, superName: String = "java/lang/Object",
                    vararg methods: MethodNode,
                    fields: List<FieldNode> = emptyList()) = ClassNode().apply {
        this.name = name
        this.superName = superName
        this.version = Opcodes.V1_8
        this.access = Opcodes.ACC_PUBLIC
        methods.forEach { this.methods.add(it) }
        fields.forEach { this.fields.add(it) }
    }

    private fun merge(client: ClassNode, server: ClassNode): ClassNode =
        DispatchMerger().mergeClass(
            "a/A", client, server,
            MergeExtension.ClassContext(null, null))!!

    @Test
    fun `divergent method bodies splice inline behind the side check`() {
        val client = cls("a/A", methods = *arrayOf(
            method("foo", "()V", Opcodes.ACC_PUBLIC,
                LdcInsnNode("client"), InsnNode(Opcodes.POP),
                InsnNode(Opcodes.RETURN))))
        val server = cls("a/A", methods = *arrayOf(
            method("foo", "()V", Opcodes.ACC_PUBLIC,
                LdcInsnNode("server"), InsnNode(Opcodes.POP),
                InsnNode(Opcodes.RETURN))))
        val merged = merge(client, server)
        // one method, both bodies inlined — no renamed side copies
        assertEquals(listOf("foo"), merged.methods.map { it.name })
        val insns = merged.methods.single().instructions.toArray()
        assertTrue(insns.filterIsInstance<MethodInsnNode>().any {
            it.owner == "java/lang/Boolean" && it.name == "getBoolean" })
        val consts = insns.filterIsInstance<LdcInsnNode>().map { it.cst }
        assertTrue("client" in consts && "server" in consts)
        // diff splice: only the LDCs diverge — the shared
        // POP+RETURN tail is emitted once
        assertEquals(1, insns.count { it.opcode == Opcodes.POP })
        assertEquals(1, insns.count { it.opcode == Opcodes.RETURN })
    }

    @Test
    fun `equal methods emit a single unrenamed member`() {
        fun m() = method("bar", "()V", Opcodes.ACC_PUBLIC,
            InsnNode(Opcodes.RETURN))
        val merged = merge(cls("a/A", methods = arrayOf(m())),
            cls("a/A", methods = arrayOf(m())))
        assertEquals(listOf("bar"), merged.methods.map { it.name })
    }

    @Test
    fun `divergent constructors splice, load and dispatch`() {
        // each side's <init> calls super then stamps a marker field —
        // the merged class must verify with a branched init call
        fun ctor(marker: String) = method("<init>", "()V",
            Opcodes.ACC_PUBLIC,
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false),
            VarInsnNode(Opcodes.ALOAD, 0),
            LdcInsnNode(marker),
            FieldInsnNode(Opcodes.PUTFIELD, "a/A", "side",
                "Ljava/lang/String;"),
            InsnNode(Opcodes.RETURN)).apply {
            maxLocals = 1
            maxStack = 2
        }
        val side = FieldNode(Opcodes.ACC_PUBLIC, "side",
            "Ljava/lang/String;", null, null)
        val merged = merge(
            cls("a/A", methods = arrayOf(ctor("client")), fields = listOf(side)),
            cls("a/A", methods = arrayOf(ctor("server")), fields = listOf(
                FieldNode(Opcodes.ACC_PUBLIC, "side",
                    "Ljava/lang/String;", null, null))))
        assertEquals(1, merged.methods.count { it.name == "<init>" })

        val bytes = ClassWriter(0).also(merged::accept).toByteArray()
        val loaded = object : ClassLoader() {
            fun define() = defineClass("a.A", bytes, 0, bytes.size)
        }.define()
        fun sideOf(): String = loaded.getDeclaredConstructor()
            .newInstance().let {
                it.javaClass.getField("side").get(it) as String
            }
        try {
            System.clearProperty("unimined.server")
            assertEquals("client", sideOf())
            System.setProperty("unimined.server", "true")
            assertEquals("server", sideOf())
        } finally {
            System.clearProperty("unimined.server")
        }
    }

    @Test
    fun `divergent clinit splices in place`() {
        fun clinit(marker: String) = method("<clinit>", "()V",
            Opcodes.ACC_STATIC,
            LdcInsnNode(marker), InsnNode(Opcodes.POP),
            InsnNode(Opcodes.RETURN))
        val merged = merge(
            cls("a/A", methods = arrayOf(clinit("client"))),
            cls("a/A", methods = arrayOf(clinit("server"))))
        assertEquals(listOf("<clinit>"),
            merged.methods.map { it.name })
        val consts = merged.methods.single().instructions.toArray()
            .filterIsInstance<LdcInsnNode>().map { it.cst }
        assertTrue("client" in consts && "server" in consts)
    }

    @Test
    fun `abstract divergence still fails loud`() {
        val client = cls("a/A", methods = arrayOf(
            method("f", "()V",
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT)))
        val server = cls("a/A", methods = arrayOf(
            method("f", "()V", Opcodes.ACC_PUBLIC,
                InsnNode(Opcodes.RETURN))))
        assertThrows<IllegalArgumentException> {
            merge(client, server)
        }
    }

    @Test
    fun `class metadata divergence still fails`() {
        val m = { method("f", "()V", Opcodes.ACC_PUBLIC,
            InsnNode(Opcodes.RETURN)) }
        assertThrows<IllegalArgumentException> {
            merge(cls("a/A", "java/lang/Object", m()),
                cls("a/A", "a/Base", m()))
        }
    }
}
