package xyz.wagyourtail.unimined.transform.conversion.internal


import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Pure-function coverage for [FoxLoaderVersionMap] — parsing,
 * dist normalization, pair lookup, default selection, and the
 * version comparator. `fetch` is Gradle/network-bound and excluded.
 */
class FoxLoaderVersionMapTest {

    private val pairs = FoxLoaderVersionMap.parse(
        """{
            "2.8.9": "2.4_1",
            "2.9": "2.4_1",
            "2.10.1": "2.4_1",
            "2.11": "2.5",
            "3.0": "2.5",
            "4.0": "2.9_03"
        }"""
    )

    @Test
    fun `parse reads foxloader to dist pairs`() {
        assertEquals("2.4_1", pairs["2.8.9"])
        assertEquals(6, pairs.size)
    }

    @Test
    fun `normalizeDist strips leading zeros after underscore`() {
        assertEquals("2.4_1", FoxLoaderVersionMap.normalizeDist("2.4_01"))
        assertEquals("2.4_1", FoxLoaderVersionMap.normalizeDist("2.4_1"))
        assertEquals("2.5", FoxLoaderVersionMap.normalizeDist("2.5"))
    }

    @Test
    fun `compatible lists foxloader versions for the dist ascending`() {
        assertEquals(
            listOf("2.8.9", "2.9", "2.10.1"),
            FoxLoaderVersionMap.compatible(pairs, "2.4_1")
        )
        assertEquals(
            listOf("2.11", "3.0"),
            FoxLoaderVersionMap.compatible(pairs, "2.5")
        )
    }

    @Test
    fun `compatible matches un-normalized map values too`() {
        // the real map ships values like `2.9_03` — normalization
        // applies on both sides so `2.9_3` and `2.9_03` both match
        assertEquals(
            listOf("4.0"),
            FoxLoaderVersionMap.compatible(pairs, "2.9_3")
        )
        assertEquals(
            listOf("4.0"),
            FoxLoaderVersionMap.compatible(pairs, "2.9_03")
        )
    }

    @Test
    fun `compatible normalizes the dist version before matching`() {
        assertEquals(
            listOf("2.8.9", "2.9", "2.10.1"),
            FoxLoaderVersionMap.compatible(pairs, "2.4_01")
        )
    }

    @Test
    fun `default picks the newest compatible release`() {
        assertEquals("2.10.1", FoxLoaderVersionMap.default(pairs, "2.4_1"))
        assertEquals("3.0", FoxLoaderVersionMap.default(pairs, "2.5"))
    }

    @Test
    fun `default throws listing known pairs when nothing matches`() {
        val e = assertThrows<IllegalArgumentException> {
            FoxLoaderVersionMap.default(pairs, "9.9")
        }
        assertTrue("9.9" in e.message!!)
    }

    @Test
    fun `resolve accepts a valid explicit pair`() {
        assertEquals("2.9", FoxLoaderVersionMap.resolve(pairs, "2.4_1", "2.9"))
    }

    @Test
    fun `resolve defaults a blank version to newest compatible`() {
        assertEquals("2.10.1", FoxLoaderVersionMap.resolve(pairs, "2.4_1", ""))
    }

    @Test
    fun `resolve rejects an incompatible pair listing valid versions`() {
        val e = assertThrows<IllegalArgumentException> {
            FoxLoaderVersionMap.resolve(pairs, "2.4_1", "3.0")
        }
        assertTrue("3.0" in e.message!!)
        assertTrue("2.10.1" in e.message!!)
        assertTrue("2.8.9" in e.message!!)
    }

    @Test
    fun `resolve rejects an unknown loader version`() {
        assertThrows<IllegalArgumentException> {
            FoxLoaderVersionMap.resolve(pairs, "2.5", "1.0")
        }
    }

    @Test
    fun `compareVersions orders dotted numerics`() {
        assertTrue(FoxLoaderVersionMap.compareVersions("2.9", "2.8.9") > 0)
        assertTrue(FoxLoaderVersionMap.compareVersions("2.10.1", "2.9") > 0)
        assertTrue(FoxLoaderVersionMap.compareVersions("2.9", "2.9") == 0)
        assertTrue(FoxLoaderVersionMap.compareVersions("3.0", "2.11") > 0)
    }

    @Test
    fun `compareVersions treats strictly-longer versions as later`() {
        assertTrue(FoxLoaderVersionMap.compareVersions("2.9.1", "2.9") > 0)
    }
}
