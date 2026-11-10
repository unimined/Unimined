package xyz.wagyourtail.unimined.migrate.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.migrate.task.pickBridgeNamespace
import xyz.wagyourtail.unimined.migrate.task.pickTargetNamespace
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.mappingTree

class MigrateNamespaceSelectionTest {

    @Test
    fun `explicit target dev-ns wins`() {
        val tree = mappingTree("official", "intermediary", "named")
        assertEquals("mcp", pickTargetNamespace(tree, "mcp"))
    }

    @Test
    fun `target falls back through preferred names then last column`() {
        assertEquals(
            "named",
            pickTargetNamespace(mappingTree("official", "intermediary", "named"), null)
        )
        assertEquals(
            "mojmap",
            pickTargetNamespace(mappingTree("official", "mojmap"), null)
        )
        assertEquals(
            "srg",
            pickTargetNamespace(mappingTree("official", "srg"), null)
        )
    }

    @Test
    fun `explicit bridge wins`() {
        val shared = setOf(Namespace("intermediary"), Namespace("official"))
        assertEquals(
            Namespace("official"),
            pickBridgeNamespace(shared, "official", listOf("intermediary"))
        )
    }

    @Test
    fun `prod namespaces preferred before intermediary then official`() {
        val shared = setOf(Namespace("intermediary"), Namespace("official"))
        assertEquals(
            Namespace("intermediary"),
            pickBridgeNamespace(shared, null, emptyList())
        )
        assertEquals(
            Namespace("official"),
            pickBridgeNamespace(setOf(Namespace("official")), null, emptyList())
        )
    }

    @Test
    fun `prod list order wins over defaults`() {
        val shared = setOf(Namespace("intermediary"), Namespace("calamus"))
        assertEquals(
            Namespace("calamus"),
            pickBridgeNamespace(shared, null, listOf("calamus"))
        )
    }

    @Test
    fun `empty shared set yields null`() {
        assertNull(pickBridgeNamespace(emptySet(), null, emptyList()))
    }
}
