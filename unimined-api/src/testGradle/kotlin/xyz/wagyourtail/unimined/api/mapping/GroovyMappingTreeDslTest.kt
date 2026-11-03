package xyz.wagyourtail.unimined.api.mapping

import groovy.lang.Closure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName

/**
 * Exercises the Groovy `Closure` path: the same fixture as
 * UML's `MappingTreeDslTest`, but driven through
 * [GroovyMappingTreeDsl] delegates the way a `build.gradle` closure
 * resolves them (`DELEGATE_FIRST`, unqualified member calls).
 */
class GroovyMappingTreeDslTest {

    @Test
    fun `closure delegates build the tree`() {
        val tree = mappingTree("official", "named", block = closure<GroovyMappingTreeDsl> { dsl ->
            dsl.pkg("a/", "b/")
            dsl.clazz("a/A", "b/B", block = closure<GroovyMappingTreeDsl.GroovyClassDsl> { cls ->
                cls.field("f;I", "g")
                cls.method("m;()V", "n", block = closure<GroovyMappingTreeDsl.GroovyMethodDsl> { m ->
                    m.parameter(0, null, null, "arg")
                    m.localVariable(0, null, null, "v")
                })
            })
        })

        val off = Namespace("official")
        val named = Namespace("named")
        assertEquals(listOf(off, named), tree.namespaces.toList())
        assertEquals(
            "b/",
            tree.packages.single().names[named]!!.value
        )
        val cls = tree.getClass(off, InternalName.unchecked("a/A"))!!
        assertEquals("b/B", cls.names[named]!!.value)
        assertEquals("g", cls.fields.single().names[named]!!.value)
        val method = cls.methods.single()
        assertEquals("arg", method.parameters.single().names[named]!!.value)
        assertEquals("v", method.localVariables.single().names[named]!!.value)
    }

    @Test
    fun `closure-free calls reach the no-block overrides`() {
        val dsl = GroovyMappingTreeDsl(listOf("official", "named"))
        dsl.clazz("a/A", "b/B")
        dsl.constantGroup(
            xyz.wagyourtail.unimined.mapping.element.InlineType.PLAIN,
            "grp", "official"
        )
        assertEquals(1, dsl.tree.classes.toList().size)
        assertEquals(1, dsl.tree.constantGroups.toList().size)
    }

    /**
     * Kotlin stand-in for a Groovy `Closure` — `DELEGATE_FIRST` is set by
     * [mappingTree]/the overloads, so `doCall` invoking members on the
     * delegate mirrors how Groovy dispatches unqualified calls.
     */
    private fun <T> closure(
        action: (T) -> Unit
    ): Closure<Any?> = object : Closure<Any?>(this) {
        fun doCall() {
            @Suppress("UNCHECKED_CAST")
            action(getDelegate() as T)
        }
    }

}
