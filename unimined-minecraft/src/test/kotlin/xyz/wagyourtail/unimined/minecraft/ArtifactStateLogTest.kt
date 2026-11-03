package xyz.wagyourtail.unimined.minecraft

import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactStateLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class ArtifactStateLogTest {

    @Test
    fun `positional queries distinguish ordering`() {
        val log = ArtifactStateLog.of("ns:X", "patched", "ns:Y")
        // patched after ns:X
        assertTrue(log.satisfiesAfter({ it == "patched" }, "ns:X"))
        // ns:Y after patched
        assertTrue(log.satisfiesAfter({ it == "ns:Y" }, "patched"))
        // ns:Y NOT before patched — order matters
        val reordered = ArtifactStateLog.of("ns:X", "ns:Y", "patched")
        assertTrue(reordered.satisfiesAfter({ it == "patched" }, "ns:Y"))
        assertFalse(reordered.satisfiesAfter({ it == "patched" }, "ns:X") &&
                reordered.satisfiesAfter({ it == "ns:Y" }, "patched") &&
                !reordered.satisfiesAfter({ it == "ns:Y" }, "ns:X"))
    }

    @Test
    fun `latestSatisfying returns last index`() {
        val log = ArtifactStateLog.of("a", "b", "a")
        assertEquals(2, log.latestSatisfying { it == "a" })
        assertEquals(1, log.latestSatisfying { it == "b" })
        assertEquals(-1, log.latestSatisfying { it == "z" })
    }

    @Test
    fun `after checks marker at or past index`() {
        val log = ArtifactStateLog.of("x", "patched", "y")
        assertTrue(log.after("y", 0))
        assertTrue(log.after("y", 2))
        assertFalse(log.after("y", 3))
        assertFalse(log.after("z", 0))
    }

    @Test
    fun `append produces new log`() {
        val log = ArtifactStateLog.EMPTY + "patched" + "ns:Y"
        assertEquals(listOf("patched", "ns:Y"), log.entries)
        assertEquals(emptyList<String>(), ArtifactStateLog.EMPTY.entries)
    }

}
