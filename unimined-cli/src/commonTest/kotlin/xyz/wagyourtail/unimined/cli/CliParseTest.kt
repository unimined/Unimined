package xyz.wagyourtail.unimined.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Parser-level floor for the scaffold commands — `test` runs the
 * command in-process; only error paths are exercised because a
 * successful parse runs generation (file IO).
 */
class CliParseTest {

    @Test
    fun `new rejects unknown option`() {
        assertNotEquals(0, New().test("--bogus").statusCode)
    }

    @Test
    fun `init rejects unknown option`() {
        assertNotEquals(0, Init().test("--bogus").statusCode)
    }

    @Test
    fun `toolchain must be an int`() {
        assertNotEquals(0, New().test("--toolchain", "abc").statusCode)
    }

    @Test
    fun `help exits zero and documents options`() {
        val result = New().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue("--group" in result.output, result.output)
        assertTrue("--loader" in result.output, result.output)
    }
}
