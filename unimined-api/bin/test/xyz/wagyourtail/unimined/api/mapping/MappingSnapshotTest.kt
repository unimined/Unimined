package xyz.wagyourtail.unimined.api.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MappingSnapshotTest {

    private val tree = object : MappingTree {
        override val namespaces = listOf("official", "named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
            visitor.visitClass("a/A", "b/B")
            visitor.visitField("a/A", "f", "I", "g")
            visitor.visitMethod("a/A", "m", "()V", "n")
            visitor.visitMethodArg("a/A", "m", "()V", 0, "arg0")
            visitor.visitMethodVar("a/A", "m", "()V", 2, "var2")
            visitor.visitPackage("a", "b")
        }
    }

    private fun collect(visitor: MappingVisitor.() -> Unit) = mutableListOf<String>().also { out ->
        object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) { out += "c:$srcName->$dstName" }
            override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) { out += "f:$srcClass.$srcName->$dstName" }
            override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) { out += "m:$srcClass.$srcName$srcDesc->$dstName" }
            override fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) { out += "arg:$index->$dstName" }
            override fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) { out += "var:$lvIndex->$dstName" }
            override fun visitPackage(srcName: String, dstName: String) { out += "p:$srcName->$dstName" }
        }.visitor()
    }

    @Test
    fun `capture and replay round-trip`() {
        val snapshot = MappingSnapshot.capture("official", "named", tree)
        assertEquals(listOf("official", "named"), snapshot.namespaces)
        assertEquals(6, snapshot.rows.size)

        val replayed = collect { snapshot.accept("official", "named", this) }
        assertEquals(
            listOf("c:a/A->b/B", "f:a/A.f->g", "m:a/A.m()V->n", "arg:0->arg0", "var:2->var2", "p:a->b"),
            replayed
        )
    }

    @Test
    fun `json round-trip preserves rows`() {
        val restored = MappingSnapshot.fromJson(
            MappingSnapshot.capture("official", "named", tree).toJson()
        )
        assertEquals("official", restored.source)
        assertEquals("named", restored.target)
        val replayed = collect { restored.accept("official", "named", this) }
        assertEquals(6, replayed.size)
        assertEquals("c:a/A->b/B", replayed.first())
    }

    @Test
    fun `accept rejects a different namespace pair`() {
        val snapshot = MappingSnapshot.capture("official", "named", tree)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.accept("named", "official", object : MappingVisitor {})
        }
    }

    @Test
    fun `snapshot exposes the tree view through SnapshotMappingData`() {
        val snapshot = MappingSnapshot.capture("official", "named", tree)
        val data = SnapshotMappingData(snapshot)
        assertEquals(snapshot, data.view(MappingTree::class.java))
    }

}
