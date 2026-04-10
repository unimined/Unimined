package xyz.wagyourtail.unimined.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GlobToRegexTest {

	@Test
	fun applyTests() {
		assertEquals("^\\/?[^/]*\\.java$", GlobToRegex.apply("*.java"))
		assertEquals("^(?:.*\\/)?\\/?[^/]*\\.java$", GlobToRegex.apply("**/*.java"))
		assertEquals("^\\/?[^/]*\\.js.$", GlobToRegex.apply("*.js?"))
		assertEquals("^(?:.*\\/)?\\.gitignore$", GlobToRegex.apply("**/.gitignore"))
		assertEquals("^\\/?[^/]*\\.js\\{on,\\}$", GlobToRegex.apply("*.js{on,}"))
		assertEquals("^\\/?[^/]*\\.js\\*$", GlobToRegex.apply("*.js\\*"))
		assertEquals("^\\/?[^/]*$", GlobToRegex.apply("./*"))
	}

}
