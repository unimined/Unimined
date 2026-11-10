package xyz.wagyourtail.unimined.minecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnvironmentPathParserTest {

    @Test
    fun `bare name resolves against the owning project`() {
        val ref = parseEnvironmentPath(":sub", "fabric")
        assertEquals(":sub", ref.projectPath)
        assertEquals("fabric", ref.environmentId)
    }

    @Test
    fun `qualified name resolves to its project`() {
        val ref = parseEnvironmentPath(":sub", ":other:fabric")
        assertEquals(":other", ref.projectPath)
        assertEquals("fabric", ref.environmentId)
    }

    @Test
    fun `leading colon means root project`() {
        val ref = parseEnvironmentPath(":sub", ":fabric")
        assertEquals(":", ref.projectPath)
        assertEquals("fabric", ref.environmentId)
    }

    @Test
    fun `trailing colon is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseEnvironmentPath(":sub", "fabric:")
        }
    }
}
