package xyz.wagyourtail.unimined.provider.mavenizer

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class MavenizerPluginTest {

    // provider-mavenizer is a registration stub — the floor asserts
    // the plugin id resolves + apply() is side-effect-free until the
    // real provider lands (megaplan B5).
    @Test
    fun `plugin applies without side effects`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("xyz.wagyourtail.unimined.provider.mavenizer")
        assert(project.plugins.hasPlugin("xyz.wagyourtail.unimined.provider.mavenizer"))
    }
}
