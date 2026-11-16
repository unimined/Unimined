package xyz.wagyourtail.unimined.api.util

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `group:name[:version]` notation → dependency predicate. */
class DependencyNotationTest {

    private val project = ProjectBuilder.builder().build()
    private fun dep(notation: String) =
        project.dependencies.create(notation)

    @Test
    fun `two-segment notation matches any version`() {
        val pred =
            dependencyNotationPredicate("com.example:obf-lib", "a test")
        assertTrue(pred(dep("com.example:obf-lib:1.0")))
        assertTrue(pred(dep("com.example:obf-lib:2.0")))
        assertFalse(pred(dep("com.example:other:1.0")))
        assertFalse(pred(dep("org.other:obf-lib:1.0")))
    }

    @Test
    fun `three-segment notation pins the version`() {
        val pred =
            dependencyNotationPredicate("com.example:obf-lib:1.0", "a test")
        assertTrue(pred(dep("com.example:obf-lib:1.0")))
        assertFalse(pred(dep("com.example:obf-lib:2.0")))
    }

    @Test
    fun `malformed notation fails with an actionable error`() {
        for (bad in listOf(
            "foo", "", ":", "g:", ":n", "g::v", "g:n:", "g:n:v:extra"
        )) {
            val e = assertThrows<GradleException> {
                dependencyNotationPredicate(
                    bad, "forceRemap on environment 'main'")
            }
            assertTrue(e.message!!.contains("group:name[:version]"), bad)
            // the rejecting spec is named, not just the notation
            assertTrue(e.message!!.contains("environment 'main'"), bad)
        }
    }

}
