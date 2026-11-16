package xyz.wagyourtail.unimined.mapping.internal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions.compare

/** Ordering over release/snapshot/legacy ids plus `empty-<v>`
 *  pseudo-version normalization. */
class MinecraftVersionsTest {

    @Test
    fun `releases compare dotted-numerically`() {
        assertTrue(compare("1.16.5", "1.17") < 0)
        assertTrue(compare("1.3", "1.2.5") > 0)
        assertEquals(0, compare("1.20.1", "1.20.1"))
    }

    @Test
    fun `empty- pseudo-versions carry the wrapped version's era`() {
        // era gates hit raw env versionIds — an empty-<v> env must
        // compare as <v>, not throw or fall into a default branch
        assertTrue(compare("empty-b1.7.3", "1.3") < 0,
            "empty-b1.7.3 is split-era")
        assertTrue(compare("empty-1.8.8", "1.3") > 0,
            "empty-1.8.8 is post-merge")
        assertTrue(compare("empty-b1.7.3", "26.0") < 0,
            "empty-b1.7.3 predates the unobf'd era")
        assertTrue(compare("empty-1.20.1", "1.14.4") > 0)
        // empty vs bare id of the same version is a tie
        assertEquals(0, compare("empty-1.2.5", "1.2.5"))
        // wrapped ids still compare normally against each other
        assertTrue(compare("empty-1.2.5", "empty-1.3") < 0)
    }

    @Test
    fun `unparseable ids still fail loudly`() {
        assertThrows(IllegalStateException::class.java) {
            compare("empty-", "1.3")
        }
        assertThrows(IllegalStateException::class.java) {
            compare("not-a-version", "1.3")
        }
    }

}
