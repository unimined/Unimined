package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import kotlin.reflect.KClass

class ViewAdapterTest {

    private val logger = Logging.getLogger(ViewAdapterTest::class.java)
    private val ctx = MappingResolver.standaloneContext("test-env", logger)

    /** minimal portable-view-only [MappingData] over a fixed row set */
    private fun portableData(
        namespaces: List<String>,
        rows: MappingVisitor.() -> Unit
    ): MappingData {
        val tree = object : MappingTree {
            override val namespaces = namespaces
            override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
                visitor.rows()
            }
        }
        return ViewMapMappingData(mapOf(MappingTree::class to tree))
    }

    @Test
    fun `adapts portable views and composes chained namespaces`() {
        val part1 = portableData(listOf("official", "named")) {
            visitClass("a/A", "b/B")
            visitField("a/A", "f", "I", "field")
            visitMethod("a/A", "m", "()V", "method")
            visitMethodArg("a/A", "m", "()V", 0, "arg0")
            visitMethodVar("a/A", "m", "()V", 2, "local")
        }
        // chains onto part1's target: same class, next hop
        val part2 = portableData(listOf("named", "parchment")) {
            visitClass("b/B", "c/C")
            visitMethod("b/B", "method", "()V", "betterName")
        }

        val composer = DefaultMappingComposer(MappingViewAdapterRegistry())
        assertTrue(composer.supports(MappingComposeRequest(listOf(part1, part2))))

        val composed = composer.compose(MappingComposeRequest(listOf(part1, part2)), ctx)
        val uml = composed.view<UmlMappingTreeView>()?.tree
        assertNotNull(uml)
        uml!!

        val official = xyz.wagyourtail.unimined.mapping.Namespace("official")
        val parchment = xyz.wagyourtail.unimined.mapping.Namespace("parchment")
        val cls = uml.getClass(official, InternalName.unchecked("a/A"))
        assertNotNull(cls)
        cls!!
        // all three namespace columns present through the chain
        assertEquals("a/A", cls.names[official]?.value)
        assertEquals("b/B", cls.names[xyz.wagyourtail.unimined.mapping.Namespace("named")]?.value)
        assertEquals("c/C", cls.names[parchment]?.value)

        val method = cls.methods.single()
        assertEquals("betterName", method.names[parchment]?.value)
        // portable arg/var rows landed in the target namespace (and the
        // composer's name-fill propagated them to parchment)
        val named = xyz.wagyourtail.unimined.mapping.Namespace("named")
        assertEquals("arg0", method.parameters.single().names[named]?.value)
        assertEquals("local", method.localVariables.single().names[named]?.value)
    }

    @Test
    fun `native uml view wins over adapted one`() {
        val umlTree = xyz.wagyourtail.unimined.mapping.MutableMappingTree().apply {
            addNamespaces(listOf(
                xyz.wagyourtail.unimined.mapping.Namespace("x"),
                xyz.wagyourtail.unimined.mapping.Namespace("y")
            ))
        }
        val umlData = xyz.wagyourtail.unimined.api.mapping.UmlMappingData(umlTree)

        val view = MappingViewAdapterRegistry().adapt(umlData, UmlMappingTreeView::class.java)
        // data already exposes UmlMappingTreeView — adapter must not fire
        assertEquals(null, view)
    }

    @Test
    fun `unadaptable part fails with a clear error`() {
        class ExoticView : MappingDataView
        val exotic = object : MappingData {
            override val viewTypes: Set<KClass<out MappingDataView>> = setOf(ExoticView::class)
            @Suppress("UNCHECKED_CAST")
            override fun <T : MappingDataView> view(type: Class<T>): T? =
                if (type == ExoticView::class.java) ExoticView() as T else null
        }
        val part1 = portableData(listOf("official", "named")) { visitClass("a/A", "b/B") }

        val composer = DefaultMappingComposer(MappingViewAdapterRegistry())
        val ex = assertThrows(GradleException::class.java) {
            composer.compose(MappingComposeRequest(listOf(part1, exotic)), ctx)
        }
        assertTrue(ex.message!!.contains("MappingViewAdapter"))
    }

}
