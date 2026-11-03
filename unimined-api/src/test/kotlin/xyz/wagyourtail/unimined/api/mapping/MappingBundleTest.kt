package xyz.wagyourtail.unimined.api.mapping

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import xyz.wagyourtail.unimined.mapping.mappingTree

class MappingBundleTest {

    private fun tree(): MutableMappingTree = mappingTree("official", "named") {
        pkg("a/", "b/")
        clazz("a/A", "b/B") {
            field("f;I", "g")
            method("m;()V", "n") {
                parameter(0, null, null, "arg0")
                localVariable(2, null, null, "var2")
            }
        }
    }

    @Test
    fun `capture emits one row per mapped element`() {
        val bundle = MappingBundle.capture("official", "named", tree())
        assertEquals(listOf("official", "named"), bundle.namespaces)
        assertEquals(6, bundle.rows.size)
        assertEquals(
            setOf(
                "p:a/->b/", "c:a/A->b/B", "f:a/A.f->g",
                "m:a/A.m->n", "arg:0->arg0", "var:2->var2"
            ),
            bundle.rows.map { row ->
                when (row.kind) {
                    MappingBundle.Kind.PACKAGE -> "p:${row.srcName}->${row.dstName}"
                    MappingBundle.Kind.CLASS -> "c:${row.srcName}->${row.dstName}"
                    MappingBundle.Kind.FIELD -> "f:${row.srcClass}.${row.srcName}->${row.dstName}"
                    MappingBundle.Kind.METHOD -> "m:${row.srcClass}.${row.srcName}->${row.dstName}"
                    MappingBundle.Kind.METHOD_ARG -> "arg:${row.index}->${row.dstName}"
                    MappingBundle.Kind.METHOD_VAR -> "var:${row.index}->${row.dstName}"
                }
            }.toSet()
        )
    }

    @Test
    fun `capture toUmlTree round-trips into a mapping tree`() {
        val bundle = MappingBundle.capture("official", "named", tree())
        val restored = bundle.toUmlTree()
        val src = restored.namespaces.first { it.name == "official" }
        val dst = restored.namespaces.first { it.name == "named" }
        val cls = restored.getClass(src, InternalName.unchecked("a/A"))
        assertNotNull(cls)
        assertEquals("b/B", cls!!.names[dst]?.value)
        val method = with(restored) {
            cls.getMethod(
                src, UnqualifiedName.unchecked("m"),
                xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor.unchecked("()V")
            )
        }
        assertEquals("n", method?.names?.get(dst)?.value)
    }

    @Test
    fun `json round-trip preserves rows`() {
        val restored = MappingBundle.fromJson(
            MappingBundle.capture("official", "named", tree()).toJson()
        )
        assertEquals("official", restored.source)
        assertEquals("named", restored.target)
        assertEquals(6, restored.rows.size)
        assertEquals(MappingBundle.Kind.CLASS, restored.rows[1].kind)
    }

    @Test
    fun `capture rejects a namespace the tree lacks`() {
        assertThrows(IllegalStateException::class.java) {
            MappingBundle.capture("named", "other", tree())
        }
    }

    @Test
    fun `bundle exposes the tree view through BundleMappingData`() {
        val data = BundleMappingData(MappingBundle.capture("official", "named", tree()))
        val tree = data.view(xyz.wagyourtail.unimined.mapping.MappingTree::class.java)
        assertNotNull(tree)
        val src = tree!!.namespaces.first { it.name == "official" }
        assertNotNull(tree.getClass(src, InternalName.unchecked("a/A")))
    }

}
