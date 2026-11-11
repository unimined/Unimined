package xyz.wagyourtail.unimined

import org.gradle.api.plugins.ExtensionAware
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.runs.RunsExtension

/**
 * Extension-name contract: sub-plugin extensions register top-level
 * under `uniminedX` names (never bare `minecraft`/`runs`/`remap`), and
 * expose themselves as `unimined.<name>` children when the frontend
 * plugin is applied — in either application order.
 */
class ExtensionNestingTest {

    @Test
    fun `frontend nests sub-plugin extensions under unimined`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("xyz.wagyourtail.unimined")

        val unimined = project.extensions.getByName("unimined") as ExtensionAware
        assertNull(project.extensions.findByName("minecraft"),
            "bare 'minecraft' must not be registered top-level")
        assertNotNull(project.extensions.findByName("uniminedMinecraft"))
        assertSame(
            project.extensions.getByType(MinecraftExtension::class.java),
            unimined.extensions.getByType(MinecraftExtension::class.java),
            "unimined.minecraft must be the uniminedMinecraft instance"
        )
        assertSame(
            project.extensions.getByType(RunsExtension::class.java),
            unimined.extensions.getByType(RunsExtension::class.java)
        )
        assertSame(
            project.extensions.getByType(RemapExtension::class.java),
            unimined.extensions.getByType(RemapExtension::class.java)
        )
    }

    @Test
    fun `standalone sub-plugin uses namespaced top-level name`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("xyz.wagyourtail.unimined.remap")

        assertNull(project.extensions.findByName("remap"),
            "bare 'remap' must not be registered top-level")
        assertNotNull(project.extensions.findByName("uniminedRemap"))
        assertNotNull(project.extensions.getByType(RemapExtension::class.java))
    }

    @Test
    fun `late frontend adopts standalone-registered extension`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        project.plugins.apply("xyz.wagyourtail.unimined")

        val unimined = project.extensions.getByName("unimined") as ExtensionAware
        assertSame(ext, unimined.extensions.getByType(MinecraftExtension::class.java),
            "pre-existing uniminedMinecraft must be adopted, not duplicated")
    }
}
