package xyz.wagyourtail.unimined.provider.mavenizer

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MavenizerPluginTest {

    // provider-mavenizer is a registration stub — the floor asserts
    // the plugin id resolves and apply() fails loudly (rather than
    // silently no-oping) until the real provider lands (megaplan B5).
    @Test
    fun `plugin apply fails with a not-implemented error`() {
        val project = ProjectBuilder.builder().build()
        val e = assertThrows<Exception> {
            project.plugins.apply("xyz.wagyourtail.unimined.provider.mavenizer")
        }
        // plugins.apply wraps plugin exceptions — check the cause chain
        val chain = generateSequence(e as Throwable) { it.cause }.mapNotNull { it.message }
        assert(chain.any { it.contains("not implemented yet") }) { e.toString() }
    }
}
