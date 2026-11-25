package xyz.wagyourtail.unimined.remapper.uml.ext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext

/** Behavioral coverage for [ClassNameLdcExtension]'s string-literal shapes. */
class ClassNameLdcExtensionTest {

    private fun rewrite(values: List<Any>): List<Any?> {
        val tree = Fixtures.treeOf()
        with(Fixtures) {
            tree.cls("a/Obf", "pkg/Target")
            tree.cls("b/Other", "pkg/Other")
            tree.cls("c/Obf\$Inner", "pkg/Target\$Inner")
        }
        val ext = ClassNameLdcExtension(
            ExtensionContext(tree, Fixtures.obf, Fixtures.named))
        val output = ClassNode().apply {
            name = "test/Holder"
            methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "strings", "()V", null, null).apply {
                values.forEach { instructions.add(LdcInsnNode(it)) }
                instructions.add(InsnNode(Opcodes.RETURN))
            })
        }
        ext.postApply(ClassNode(), output, null)
        return output.methods.single { it.name == "strings" }.instructions
            .toArray().filterIsInstance<LdcInsnNode>().map { it.cst }
    }

    @Test
    fun `method and type descriptor literals remap every object type`() {
        assertEquals(listOf(
            "(Lpkg/Target;I)Lpkg/Other;",
            "Lpkg/Target;",
            "[Lpkg/Target;",
            "([Lpkg/Target;)V",
            "(Lpkg/Target\$Inner;)V",
        ), rewrite(listOf(
            "(La/Obf;I)Lb/Other;",
            "La/Obf;",
            "[La/Obf;",
            "([La/Obf;)V",
            "(Lc/Obf\$Inner;)V",
        )))
    }

    @Test
    fun `bare internal names remap only when the tree changes them`() {
        assertEquals(listOf(
            "pkg/Target",
            "pkg/Target\$Inner",
            "missing/Class",
            "a.Obf",
        ), rewrite(listOf(
            "a/Obf",
            "c/Obf\$Inner",
            "missing/Class",
            // dotted literals are deliberately not guessed at — too easy
            // to collide with ordinary user-facing text
            "a.Obf",
        )))
    }

    @Test
    fun `ordinary strings non strings and descriptor lookalikes pass through`() {
        assertEquals(listOf(
            "plain text",
            "not/a/mapped/class",
            "(foo)bar",
            42,
        ), rewrite(listOf(
            "plain text",
            "not/a/mapped/class",
            // the regex shape hits, but JVM descriptor parsing rejects it —
            // parse rejection must mean verbatim pass-through, not failure
            "(foo)bar",
            42,
        )))
    }
}
