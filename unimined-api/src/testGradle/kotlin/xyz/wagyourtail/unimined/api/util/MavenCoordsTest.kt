package xyz.wagyourtail.unimined.api.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class MavenCoordsTest {

    @Test
    fun `full coordinate parses every field`() {
        val c = MavenCoords("net.fabricmc:fabric-loader:0.16.0")
        assertEquals("net.fabricmc", c.group)
        assertEquals("fabric-loader", c.artifact)
        assertEquals("0.16.0", c.version)
        assertNull(c.classifier)
        assertEquals("jar", c.extension)
        assertEquals("fabric-loader-0.16.0.jar", c.fileName)
    }

    @Test
    fun `classifier and extension parse`() {
        val c = MavenCoords("g:a:1.0:sources@zip")
        assertEquals("a", c.artifact)
        assertEquals("sources", c.classifier)
        assertEquals("zip", c.extension)
        assertEquals("a-1.0-sources.zip", c.fileName)
    }

    @Test
    fun `bare single-part coordinate with extension strips the suffix`() {
        // regression: artifact returned the raw value including @zip
        val c = MavenCoords("foo@zip")
        assertEquals("foo", c.artifact)
        assertEquals("zip", c.extension)
        assertNull(c.group)
        assertNull(c.version)
    }

    @Test
    fun `bare single-part coordinate without extension`() {
        val c = MavenCoords("foo")
        assertEquals("foo", c.artifact)
        assertEquals("jar", c.extension)
        assertEquals("foo-unspecified.jar", c.fileName)
    }

    @Test
    fun `two-part coordinate has no version`() {
        val c = MavenCoords("g:a")
        assertEquals("g", c.group)
        assertEquals("a", c.artifact)
        assertNull(c.version)
        assertEquals("a-unspecified.jar", c.fileName)
    }

    @Test
    fun `constructor rejects classifier without version`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MavenCoords("g", "a", null, "sources")
        }
    }
}
