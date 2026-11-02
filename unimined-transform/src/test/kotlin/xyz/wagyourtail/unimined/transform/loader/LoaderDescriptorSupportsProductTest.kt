package xyz.wagyourtail.unimined.transform.loader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Coverage for [LoaderDescriptor.supportsProduct] — the env-binding
 * loader×product check: `null` = native only, `*` = any product,
 * exact ids, and scope prefixes (`custom` accepts `custom:<name>`,
 * `minecraft` accepts `minecraft:*`).
 */
class LoaderDescriptorSupportsProductTest {

    private fun descriptor(products: Set<String>?) = LoaderDescriptor(
        id = "test",
        family = "test",
        repos = emptyList(),
        loaderCoords = { _, v -> "g:a:$v" },
        supportedProducts = products,
        prodNamespace = "official",
    )

    @Test
    fun `null supportedProducts accepts only the native product`() {
        val d = descriptor(null)
        assertTrue(d.supportsProduct("minecraft:native"))
        assertFalse(d.supportsProduct("minecraft:reindev"))
        assertFalse(d.supportsProduct("custom:foo"))
    }

    @Test
    fun `star accepts every product`() {
        val d = descriptor(setOf("*"))
        assertTrue(d.supportsProduct("minecraft:native"))
        assertTrue(d.supportsProduct("minecraft:reindev"))
        assertTrue(d.supportsProduct("custom:foo"))
    }

    @Test
    fun `exact product ids match only themselves`() {
        val d = descriptor(setOf("minecraft:reindev"))
        assertTrue(d.supportsProduct("minecraft:reindev"))
        assertFalse(d.supportsProduct("minecraft:bta"))
        assertFalse(d.supportsProduct("minecraft:native"))
    }

    @Test
    fun `custom scope prefix accepts any out-of-family dist`() {
        val d = descriptor(setOf("custom"))
        assertTrue(d.supportsProduct("custom:foo"))
        assertTrue(d.supportsProduct("custom:bar-baz"))
        assertFalse(d.supportsProduct("minecraft:custom"))
        assertFalse(d.supportsProduct("minecraft:native"))
    }

    @Test
    fun `minecraft scope prefix accepts the family but not custom`() {
        val d = descriptor(setOf("minecraft"))
        assertTrue(d.supportsProduct("minecraft:reindev"))
        assertTrue(d.supportsProduct("minecraft:bta"))
        assertFalse(d.supportsProduct("custom:foo"))
    }

    @Test
    fun `mixed entries combine`() {
        val d = descriptor(setOf("minecraft:reindev", "custom"))
        assertTrue(d.supportsProduct("minecraft:reindev"))
        assertTrue(d.supportsProduct("custom:anything"))
        assertFalse(d.supportsProduct("minecraft:bta"))
    }
}
