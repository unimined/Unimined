package xyz.wagyourtail.unimined.remapper.uml

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.classBytes
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.cls
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.named
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.obf
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.readClasses
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.treeOf
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.writeJar
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * `Options.fixInnerClasses` gate — 1.x `provider.fixInners`
 * (`obfuscated`, applied `mc < 1.8.2` only). The rebuild is needed for
 * obf-era jars whose InnerClasses attributes were stripped; on modern
 * input it stamps fake member entries on kotlin-style `$` synthetics,
 * so the flag must be honored both ways.
 */
class FixInnerClassesGateTest {

    private val dir = createTempDirectory("fix-inner-gate")

    private fun inputJar(): Path {
        val input = dir.resolve("in.jar")
        writeJar(input, mapOf(
            "Outer.class" to classBytes("Outer"),
            "Outer\$Inner.class" to classBytes("Outer\$Inner"),
        ))
        return input
    }

    private fun remap(fixInners: Boolean): Map<String, ClassNode> {
        val out = dir.resolve("out-$fixInners.jar")
        AsmRemapper.remap(inputJar(), out, treeOf().apply {
            cls("Outer", "com/x/Outer")
            cls("Outer\$Inner", "com/x/Outer\$Inner")
        }, obf, named, Options(fixInnerClasses = fixInners))
        return readClasses(out)
    }

    @Test
    fun `rebuilds missing InnerClasses entries when enabled`() {
        val out = remap(true)
        val inner = out.getValue("com/x/Outer\$Inner")
        assertTrue(inner.innerClasses.any {
            it.name == "com/x/Outer\$Inner" &&
                    it.outerName == "com/x/Outer" &&
                    it.innerName == "Inner"
        }, "expected InnerClasses entry on inner class, got ${inner.innerClasses}")
    }

    @Test
    fun `leaves InnerClasses attributes alone when disabled`() {
        val out = remap(false)
        assertTrue(out.getValue("com/x/Outer\$Inner").innerClasses.isEmpty())
        assertTrue(out.getValue("com/x/Outer").innerClasses.isEmpty())
    }

    @Test
    fun `well-formed entries are untouched either way`() {
        // an inner class already carrying its InnerClasses entry must
        // not gain a duplicate under the enabled path
        val input = dir.resolve("wellformed.jar")
        writeJar(input, mapOf(
            "Outer.class" to classBytes("Outer"),
            "Outer\$Inner.class" to classBytes("Outer\$Inner") {
                innerClasses.add(
                    org.objectweb.asm.tree.InnerClassNode(
                        "Outer\$Inner", "Outer", "Inner", 0))
            },
        ))
        val out = dir.resolve("wellformed-out.jar")
        AsmRemapper.remap(input, out, treeOf().apply {
            cls("Outer", "com/x/Outer")
            cls("Outer\$Inner", "com/x/Outer\$Inner")
        }, obf, named, Options(fixInnerClasses = true))
        val inner = readClasses(out).getValue("com/x/Outer\$Inner")
        assertFalse(inner.innerClasses.isEmpty())
        assertTrue(inner.innerClasses.count {
            it.name == "com/x/Outer\$Inner"
        } == 1, "expected exactly one InnerClasses entry, got ${inner.innerClasses}")
    }

}
