package xyz.wagyourtail.unimined.api.access

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mapping.mappingTree

class AccessModelTest {

    private fun model() = AccessModel(
        listOf(
            AccessModel.Section(
                "named",
                listOf(
                    AccessModel.Entry(
                        AccessModel.Kind.CLASS, "a/A",
                        change = AccessModel.Change(0x0001, 7),
                        source = "mod.jar", transitive = true
                    ),
                    AccessModel.Entry(
                        AccessModel.Kind.METHOD, "a/A", "m", "()V",
                        AccessModel.Change(0x0004, 7),
                        source = "mod.jar"
                    ),
                    AccessModel.Entry(
                        AccessModel.Kind.FIELD, "a/A", "f", "I",
                        AccessModel.Change(0x0001, 7)
                    )
                )
            )
        )
    )

    @Test
    fun `serialize then parse round-trips sections entries and provenance`() {
        val parsed = AccessModel.parse(model().serialize())
        assertEquals(1, parsed.sections.size)
        val section = parsed.sections.single()
        assertEquals("named", section.namespace)
        assertEquals(3, section.entries.size)

        val cls = section.entries[0]
        assertEquals(AccessModel.Kind.CLASS, cls.kind)
        assertEquals("a/A", cls.owner)
        assertEquals(0x0001, cls.change.set)
        assertEquals(7, cls.change.clear)
        assertEquals("mod.jar", cls.source)
        assertTrue(cls.transitive)

        val method = section.entries[1]
        assertEquals("m", method.name)
        assertEquals("()V", method.desc)
        assertEquals(0x0004, method.change.set)
        assertEquals("mod.jar", method.source)
    }

    @Test
    fun `rules resolves lookups with desc-agnostic fallback`() {
        val rules = model().rules("named")
        assertEquals(0x0001, rules.classChange("a/A")?.set)
        assertEquals(0x0004, rules.methodChange("a/A", "m", "()V")?.set)
        // desc not in the model -> falls back to the name-only rule
        assertEquals(0x0001, rules.fieldChange("a/A", "f", "J")?.set)
        assertNull(rules.classChange("other/C"))
        assertNull(rules.methodChange("a/A", "x", "()V"))
    }

    @Test
    fun `rules rebases foreign sections through the mapping tree`() {
        val tree = mappingTree("official", "named") {
            clazz("z/Z", "a/A") {
                method("x;()V", "m")
            }
        }
        val model = AccessModel(
            listOf(
                AccessModel.Section(
                    "official",
                    listOf(
                        AccessModel.Entry(
                            AccessModel.Kind.CLASS, "z/Z",
                            change = AccessModel.Change(0x0001, 7)
                        ),
                        AccessModel.Entry(
                            AccessModel.Kind.METHOD, "z/Z", "x", "()V",
                            AccessModel.Change(0x0001, 7)
                        )
                    )
                )
            )
        )
        val rules = model.rules("named", tree)
        assertEquals(0x0001, rules.classChange("a/A")?.set)
        assertEquals(0x0001, rules.methodChange("a/A", "m", "()V")?.set)
        assertNull(rules.classChange("z/Z"))
    }

    @Test
    fun `rules skips foreign sections without a tree`() {
        val rules = model().rules("official")
        assertTrue(rules.isEmpty)
    }

    @Test
    fun `merge concatenates sections and empty is empty`() {
        val merged = AccessModel.merge(listOf(model(), AccessModel.EMPTY))
        assertEquals(1, merged.sections.size)
        assertTrue(AccessModel.EMPTY.isEmpty)
        assertTrue(AccessModel.parse(AccessModel.EMPTY.serialize()).isEmpty)
    }

    @Test
    fun `duplicate keys merge masks`() {
        val model = AccessModel(
            listOf(
                AccessModel.Section(
                    "named",
                    listOf(
                        AccessModel.Entry(
                            AccessModel.Kind.CLASS, "a/A",
                            change = AccessModel.Change(0x0001, 1)
                        ),
                        AccessModel.Entry(
                            AccessModel.Kind.CLASS, "a/A",
                            change = AccessModel.Change(0, 2)
                        )
                    )
                )
            )
        )
        val change = model.rules("named").classChange("a/A")!!
        assertEquals(0x0001, change.set)
        assertEquals(3, change.clear)
    }

}
