package xyz.wagyourtail.unimined.transform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeRegistry

class StepTypeRegistryTest {

    private fun type(id: String, requires: Set<String> = emptySet(),
                     runsAfter: Set<String> = emptySet()) = object : StepType {
        override val id = id
        override val requires = requires
        override val runsAfter = runsAfter
    }

    @Test
    fun `topological order satisfies requires`() {
        val reg = StepTypeRegistry()
        val a = type("x:a")
        val b = type("x:b", requires = setOf("x:a"))
        val c = type("x:c", requires = setOf("x:b"))
        assertEquals(listOf("x:a", "x:b", "x:c"),
            reg.resolveOrder(listOf(c, b, a)).map { it.id })
    }

    @Test
    fun `registration order preserved when unconstrained`() {
        val reg = StepTypeRegistry()
        val a = type("x:a"); val b = type("x:b"); val c = type("x:c")
        assertEquals(listOf("x:b", "x:c", "x:a"),
            reg.resolveOrder(listOf(b, c, a)).map { it.id })
    }

    @Test
    fun `missing required dep fails loudly`() {
        val reg = StepTypeRegistry()
        assertThrows<IllegalArgumentException> {
            reg.resolveOrder(listOf(type("x:a", requires = setOf("x:absent"))))
        }
    }

    @Test
    fun `cycle fails loudly`() {
        val reg = StepTypeRegistry()
        assertThrows<IllegalArgumentException> {
            reg.resolveOrder(listOf(
                type("x:a", requires = setOf("x:b")),
                type("x:b", requires = setOf("x:a"))))
        }
    }

    @Test
    fun `marker collision fails at registration`() {
        val reg = StepTypeRegistry()
        reg.register(object : StepType {
            override val id = "x:a"
            override val produces = listOf("shared:marker")
        })
        assertThrows<IllegalArgumentException> {
            reg.register(object : StepType {
                override val id = "x:b"
                override val produces = listOf("shared:marker")
            })
        }
    }

    @Test
    fun `runsAfter only constrains when present`() {
        val reg = StepTypeRegistry()
        val a = type("x:a", runsAfter = setOf("x:b"))
        assertEquals(listOf("x:a"), reg.resolveOrder(listOf(a)).map { it.id })
        val b = type("x:b")
        assertEquals(listOf("x:b", "x:a"),
            reg.resolveOrder(listOf(a, b)).map { it.id })
    }

}
