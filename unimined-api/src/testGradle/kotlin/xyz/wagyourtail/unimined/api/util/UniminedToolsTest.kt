package xyz.wagyourtail.unimined.api.util

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [UniminedTools] registry semantics: known tool coordinates route
 * through their (overridable) properties, unknown coords pass
 * through, and the extension is a per-project singleton.
 */
class UniminedToolsTest {

    private val project = ProjectBuilder.builder().build()

    @Test
    fun `known coords remap to their tool property`() {
        val tools = project.uniminedTools()
        assertEquals(
            "net.neoforged.jst:jst-cli:2.0.11",
            tools.remapToolCoord("net.neoforged.jst:jst-cli:2.0.11")
        )
        // override wins regardless of the declared version
        tools.jst.set("net.neoforged.jst:jst-cli:3.0.0")
        assertEquals(
            "net.neoforged.jst:jst-cli:3.0.0",
            tools.remapToolCoord("net.neoforged.jst:jst-cli:2.0.11")
        )
    }

    @Test
    fun `unrelated and profile-declared coords pass through`() {
        val tools = project.uniminedTools()
        tools.diffpatch.set("com.example:DiffPatchFork:1.0")
        // a forked coordinate is not the known tool g:a — untouched
        assertEquals(
            "com.example:DiffPatchFork:9.9",
            tools.remapToolCoord("com.example:DiffPatchFork:9.9")
        )
        // a random profile-declared coord is not a known tool
        assertEquals(
            "org.ow2.asm:asm:9.9",
            tools.remapToolCoord("org.ow2.asm:asm:9.9")
        )
        // non-coordinate strings don't break the parser
        assertEquals("single-segment", tools.remapToolCoord("single-segment"))
    }

    @Test
    fun `suffix forms still match`() {
        val tools = project.uniminedTools()
        tools.classpathPluginLoader.set("com.example:cpl:2.0:all")
        assertEquals(
            "com.example:cpl:2.0:all",
            tools.remapToolCoord(
                "xyz.wagyourtail.unimined:classpath-plugin-loader:" +
                        "0.1.0-SNAPSHOT:all"
            )
        )
        assertEquals(
            "net.neoforged.jst:jst-cli:2.0.11",
            tools.remapToolCoord("net.neoforged.jst:jst-cli:2.0.11@jar")
        )
    }

    @Test
    fun `extension is a per-project singleton`() {
        assertSame(project.uniminedTools(), project.uniminedTools())
        assertSame(
            project.extensions.findByName("uniminedTools"),
            project.uniminedTools()
        )
    }
}
