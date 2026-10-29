package xyz.wagyourtail.unimined.api.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpiSelectorTest {

    private data class FakeSpec(val maxVersion: Int)

    private class FakeImpl(
        override val name: String,
        private val maxVersion: Int,
        override val priority: Int = 0
    ) : Supports<FakeSpec> {
        override fun supports(spec: FakeSpec) = spec.maxVersion <= maxVersion
    }

    @Test
    fun `select returns the single supporting impl`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        val a = FakeImpl("a", 10)
        val b = FakeImpl("b", 20)
        selector.register(a)
        selector.register(b)

        assertSame(b, selector.select(FakeSpec(maxVersion = 15)))
    }

    @Test
    fun `override bypasses supports`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        val a = FakeImpl("a", 10)
        selector.register(a)

        assertSame(a, selector.select(FakeSpec(maxVersion = 99), override = "a"))
    }

    @Test
    fun `unknown override throws`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        selector.register(FakeImpl("a", 10))

        val e = assertThrows<SpiSelectionException> {
            selector.select(FakeSpec(1), override = "missing")
        }
        assertEquals(
            "unknown test impl 'missing'; registered: a",
            e.message
        )
    }

    @Test
    fun `zero candidates throws`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        selector.register(FakeImpl("a", 10))
        selector.register(FakeImpl("b", 20))

        val e = assertThrows<SpiSelectionException> {
            selector.select(FakeSpec(maxVersion = 99))
        }
        assertEquals(
            "no test impl supports FakeSpec(maxVersion=99); registered: a, b",
            e.message
        )
    }

    @Test
    fun `unique top priority wins ambiguous selection`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        val a = FakeImpl("a", 20, priority = 5)
        selector.register(a)
        selector.register(FakeImpl("b", 20))

        assertSame(a, selector.select(FakeSpec(maxVersion = 10)))
    }

    @Test
    fun `ambiguous candidates throws`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        selector.register(FakeImpl("a", 20))
        selector.register(FakeImpl("b", 20))

        val e = assertThrows<SpiSelectionException> {
            selector.select(FakeSpec(maxVersion = 10))
        }
        assertEquals(
            "ambiguous test impl for FakeSpec(maxVersion=10): a, b; " +
                    "disambiguate via DSL override",
            e.message
        )
    }

    @Test
    fun `duplicate names are rejected`() {
        val selector = spiSelector<FakeImpl, FakeSpec>("test impl")
        selector.register(FakeImpl("a", 10))

        assertThrows<IllegalArgumentException> {
            selector.register(FakeImpl("a", 20))
        }
    }

}
