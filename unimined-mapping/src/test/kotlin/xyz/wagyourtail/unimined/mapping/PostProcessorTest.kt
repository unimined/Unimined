package xyz.wagyourtail.unimined.mapping

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.processors.InitCompletionPostProcessor
import kotlin.reflect.KClass

/**
 * [MappingPostProcessorRegistry] + the builtin
 * [InitCompletionPostProcessor]: ordering, in-place semantics, no-op on
 * data without a mutable UML view, and `<init>` cell completion through
 * the ServiceLoader default.
 */
class PostProcessorTest {

    private val ctx = MappingResolver.standaloneContext("test-env",
        Logging.getLogger(PostProcessorTest::class.java))

    /** UML data with an `<init>` row in `official` only. */
    private fun umlDataWithInit(): UmlMappingData {
        val official = Namespace("official")
        val named = Namespace("named")
        val tree = MutableMappingTree().apply {
            addNamespaces(listOf(official, named))
        }
        tree.clazz(mapOf(
            official to xyz.wagyourtail.unimined.mapping.jvms.four.two.one
                .InternalName.unchecked("a/A"),
            named to xyz.wagyourtail.unimined.mapping.jvms.four.two.one
                .InternalName.unchecked("b/B")
        )).method(mapOf(
            official to xyz.wagyourtail.unimined.mapping.jvms.ext
                .MethodNameAndDescriptor.unchecked("<init>;(La/A;)V")
        ))
        return UmlMappingData(tree)
    }

    private fun portableOnlyData(): MappingData {
        val tree = object : MappingTree {
            override val namespaces = listOf("a", "b")
            override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {}
        }
        return ViewMapMappingData(mapOf<KClass<out MappingDataView>, MappingDataView>(
            MappingTree::class to tree))
    }

    @Test
    fun `init completion fills missing namespace cells`() {
        val data = umlDataWithInit()
        InitCompletionPostProcessor().process(data, ctx)

        val named = Namespace("named")
        val tree = data.view<UmlMappingTreeView>()!!.tree
        val init = tree.classes.single().methods.single()
        assertEquals("<init>", init.names[named]?.value)
        // donor desc remapped through the class mapping, not copied
        assertEquals("(Lb/B;)V", init.descs[named]?.value)
    }

    @Test
    fun `init completion no-ops without uml view`() {
        val data = portableOnlyData()
        val result = InitCompletionPostProcessor().process(data, ctx)
        assertSame(data, result)
    }

    @Test
    fun `registry folds processors in order`() {
        val calls = mutableListOf<String>()
        fun recorder(tag: String) = object : MappingPostProcessor {
            override val name = tag
            override fun process(data: MappingData, ctx: MappingContext): MappingData {
                calls.add(tag); return data
            }
        }
        val registry = MappingPostProcessorRegistry()
        registry.register(recorder("first"))
        registry.register(recorder("second"))

        registry.process(portableOnlyData(), ctx)
        // ServiceLoader defaults run first; user-registered processors follow
        assertEquals(listOf("first", "second"), calls.takeLast(2))
    }

    @Test
    fun `serviceloader default is registered`() {
        val names = MappingPostProcessorRegistry().processors.map { it.name }
        assertTrue("init-completion" in names)
    }

}
