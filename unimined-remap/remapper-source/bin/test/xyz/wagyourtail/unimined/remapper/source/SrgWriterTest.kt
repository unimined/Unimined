package xyz.wagyourtail.unimined.remapper.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import java.nio.file.Path
import kotlin.io.path.readLines

class SrgWriterTest {

    @TempDir
    lateinit var dir: Path

    /** `official -> named`: pkg a→b, class a/A→b/B, field f→g, method m(I)V→n. */
    private val tree = object : MappingTree {
        override val namespaces = listOf("official", "named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
            visitor.visitPackage("a", "b")
            visitor.visitClass("a/A", "b/B")
            visitor.visitField("a/A", "f", "I", "g")
            visitor.visitMethod("a/A", "m", "(La/A;)V", "n")
            // member of a class only moved by the package row
            visitor.visitMethod("a/C", "m2", "()La/A;", "n2")
            // not expressible in SRG — must be skipped without failing
            visitor.visitMethodArg("a/A", "m", "(La/A;)V", 0, "arg")
        }
    }

    @Test
    fun `writes srg with desc and package-prefix remapping`() {
        val out = dir.resolve("map.srg")
        SrgWriter.write(tree, "official", "named", out)

        val lines = out.readLines()
        assertTrue("PK: a b" in lines)
        assertTrue("CL: a/A b/B" in lines)
        assertTrue("FD: a/A/f b/B/g" in lines)
        // method owner and descriptor both remapped
        assertTrue("MD: a/A/m (La/A;)V b/B/n (Lb/B;)V" in lines)
        // member of class only renamed via the package row gets the
        // package-remapped owner too
        assertTrue("MD: a/C/m2 ()La/A; b/C/n2 ()Lb/B;" in lines)
        // args/vars are skipped — no partial SRG rows
        assertTrue(lines.none { it.contains("arg") })
        assertEquals(5, lines.size)
    }

}
