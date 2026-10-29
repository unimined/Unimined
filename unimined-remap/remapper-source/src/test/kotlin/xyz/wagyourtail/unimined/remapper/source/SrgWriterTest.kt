package xyz.wagyourtail.unimined.remapper.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.mappingTree
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * [SourceRemapStep.writeSrg] over UML's `SrgWriter`: rows emit for both
 * namespaces, method dst descriptors are filled from the remapped source
 * descriptor, and param/var rows are not expressible in SRG.
 */
class SrgWriterTest {

    @TempDir
    lateinit var dir: Path

    /**
     * `official -> named`: pkg a→b, class a/A→b/B, field f→g,
     * method m(La/A;)V→n. `a/C` exists only in official — the PK row
     * still remaps it inside descriptors.
     */
    private val tree = mappingTree("official", "named") {
        pkg("a/", "b/")
        clazz("a/A", "b/B") {
            field("f;I", "g")
            method("m;(La/A;)V", "n") {
                parameter(0, null, null, "arg")
            }
            // desc referencing the package-only-moved class
            method("m2;(La/C;)V", "n2")
        }
        clazz("a/C")
    }

    @Test
    fun `writes srg with desc and package-prefix remapping`() {
        val out = dir.resolve("map.srg")
        SourceRemapStep().writeSrg(tree, "official", "named", out)

        val lines = out.readLines()
        assertTrue("PK: a b" in lines)
        assertTrue("CL: a/A b/B" in lines)
        assertTrue("FD: a/A/f b/B/g" in lines)
        // method owner and descriptor both remapped
        assertTrue("MD: a/A/m (La/A;)V b/B/n (Lb/B;)V" in lines)
        // desc refs to a class with no row still get the package rename
        assertTrue("MD: a/A/m2 (La/C;)V b/B/n2 (Lb/C;)V" in lines)
        // args/vars are skipped — no partial SRG rows
        assertTrue(lines.none { it.contains("arg") })
        assertEquals(5, lines.size)
    }

}
