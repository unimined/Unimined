package xyz.wagyourtail.unimined.provider.`native`

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure-string substitution semantics (1.x `replaceLibraryVersion`). */
class LibrarySubstitutionTest {

    @Test
    fun `matching group-name prefix swaps only the version`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.8",
            substitute("org.lwjgl.lwjgl:lwjgl:2.9.1", listOf("org.lwjgl" to "2.9.4+legacyfabric.8"))
        )
    }

    @Test
    fun `non-matching prefix passes through untouched`() {
        assertEquals(
            "com.google.code.gson:gson:2.2.4",
            substitute("com.google.code.gson:gson:2.2.4", listOf("org.lwjgl" to "9.9.9"))
        )
    }

    @Test
    fun `narrower prefix only matches its own artifacts`() {
        val rules = listOf("org.lwjgl.lwjgl" to "2.9.4+legacyfabric.10")
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl_util:2.9.4+legacyfabric.10",
            substitute("org.lwjgl.lwjgl:lwjgl_util:2.9.1", rules)
        )
        assertEquals(
            "org.lwjgl.opengl:gl:3.3.1",
            substitute("org.lwjgl.opengl:gl:3.3.1", rules)
        )
    }

    @Test
    fun `classifier and extension segments survive the swap`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.8:natives-linux",
            substitute(
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux",
                listOf("org.lwjgl" to "2.9.4+legacyfabric.8")
            )
        )
    }

    @Test
    fun `first matching rule wins`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl:A",
            substitute("org.lwjgl.lwjgl:lwjgl:1.0", listOf("org.lwjgl" to "A", "org.lwjgl" to "B"))
        )
    }

    @Test
    fun `malformed coords pass through`() {
        assertEquals("novarsion", substitute("novarsion", listOf("nov" to "1.0")))
        assertEquals("g:n", substitute("g:n", listOf("g" to "1.0")))
    }
}
