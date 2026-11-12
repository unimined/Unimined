package xyz.wagyourtail.unimined.provider.`native`

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.spec.LibraryRule
import xyz.wagyourtail.unimined.api.spec.applyLibraryRules

/** Pure-string substitution semantics (1.x `replaceLibraryVersion`). */
class LibrarySubstitutionTest {

    @Test
    fun `matching group-name prefix swaps only the version`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.8",
            applyLibraryRules("org.lwjgl.lwjgl:lwjgl:2.9.1", listOf("org.lwjgl" to "2.9.4+legacyfabric.8"))
        )
    }

    @Test
    fun `non-matching prefix passes through untouched`() {
        assertEquals(
            "com.google.code.gson:gson:2.2.4",
            applyLibraryRules("com.google.code.gson:gson:2.2.4", listOf("org.lwjgl" to "9.9.9"))
        )
    }

    @Test
    fun `narrower prefix only matches its own artifacts`() {
        val rules = listOf("org.lwjgl.lwjgl" to "2.9.4+legacyfabric.10")
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl_util:2.9.4+legacyfabric.10",
            applyLibraryRules("org.lwjgl.lwjgl:lwjgl_util:2.9.1", rules)
        )
        assertEquals(
            "org.lwjgl.opengl:gl:3.3.1",
            applyLibraryRules("org.lwjgl.opengl:gl:3.3.1", rules)
        )
    }

    @Test
    fun `classifier and extension segments survive the swap`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.8:natives-linux",
            applyLibraryRules(
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux",
                listOf("org.lwjgl" to "2.9.4+legacyfabric.8")
            )
        )
    }

    @Test
    fun `first matching rule wins`() {
        assertEquals(
            "org.lwjgl.lwjgl:lwjgl:A",
            applyLibraryRules("org.lwjgl.lwjgl:lwjgl:1.0", listOf("org.lwjgl" to "A", "org.lwjgl" to "B"))
        )
    }

    @Test
    fun `malformed coords pass through`() {
        assertEquals("novarsion", applyLibraryRules("novarsion", listOf("nov" to "1.0")))
        assertEquals("g:n", applyLibraryRules("g:n", listOf("g" to "1.0")))
    }

    @Test
    fun `rich rule null version drops the library`() {
        val rules = listOf(LibraryRule(
            group = Regex("ca\\.weblite"), name = Regex("java-objc-bridge"),
            classifier = Regex("natives-osx")) { null })
        assertEquals(null, applyLibraryRules(
            "ca.weblite:java-objc-bridge:1.1.0:natives-osx", emptyList(), rules))
        // classifier regex is only consulted for classified coords —
        // the bare g:n:v form drops too (1.x parity)
        assertEquals(null, applyLibraryRules(
            "ca.weblite:java-objc-bridge:1.1.0", emptyList(), rules))
        assertEquals("ca.weblite:something-else:1.1.0", applyLibraryRules(
            "ca.weblite:something-else:1.1.0", emptyList(), rules))
    }

    @Test
    fun `rich rule regex rewrites the version`() {
        val rules = listOf(LibraryRule(
            group = Regex("org\\.lwjgl.*"), name = Regex("lwjgl(-.*)?")
        ) { "2.9.4+legacyfabric.8" })
        assertEquals("org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.8",
            applyLibraryRules("org.lwjgl.lwjgl:lwjgl:2.9.1", emptyList(), rules))
        assertEquals("org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.8:natives-linux",
            applyLibraryRules(
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux",
                emptyList(), rules))
    }

    @Test
    fun `default rules drop the osx objc-bridge natives`() {
        assertEquals(null, applyLibraryRules(
            "ca.weblite:java-objc-bridge:1.1.0:natives-osx", emptyList()))
    }

    @Test
    fun `coord filter rewrites before rules see it`() {
        val filters = listOf<(String) -> String?> {
            it.replace("1.0", "2.0") }
        assertEquals("g:a:2.0", applyLibraryRules(
            "g:a:1.0", emptyList(), emptyList(), filters))
    }
}
