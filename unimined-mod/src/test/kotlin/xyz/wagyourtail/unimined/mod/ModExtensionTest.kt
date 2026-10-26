package xyz.wagyourtail.unimined.mod

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ModExtensionTest {

    @TempDir
    lateinit var dir: Path

    private fun extension(): ModExtension {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile())
            .build()
        return project.extensions.create("mod", ModExtension::class.java, project)
    }

    @Test
    fun `model snapshots loader-neutral fields`() {
        val mod = extension()
        mod.id.set("testmod")
        mod.name.set("Test Mod")
        mod.authors.add("someone")
        mod.depend("fabricloader", ">=0.16.0")
        mod.environment.set("client")

        val meta = mod.snapshot()
        assertEquals("testmod", meta.id)
        assertEquals("Test Mod", meta.name)
        assertEquals(listOf("someone"), meta.authors)
        assertEquals(mapOf("fabricloader" to ">=0.16.0"), meta.depends)
        assertEquals("client", meta.environment)
        assertNull(meta.description)
    }

    @Test
    fun `version defaults to the project version`() {
        val mod = extension()
        assertEquals("unspecified", mod.version.get())
    }

    @Test
    fun `loader sections accumulate per loader name`() {
        val mod = extension()
        mod.loader("fabric") { it.entrypoint("main", "example.Mod") }
        mod.loader("fabric") { it.entrypoint("client", "example.ModClient") }
        mod.loader("fabric") { it.accessWidener("testmod.accesswidener") }

        val section = mod.snapshot().sections["fabric"]
        assertNotNull(section)
        assertEquals(
            mapOf("main" to listOf("example.Mod"), "client" to listOf("example.ModClient")),
            section!!.entrypoints
        )
        assertEquals("testmod.accesswidener", section.accessWidener)
    }

    @Test
    fun `loader without a registered emitter fails clearly`() {
        val mod = extension()
        val ex = assertThrows(GradleException::class.java) {
            mod.loader("not-a-loader") { }
        }
        assert(ex.message!!.contains("no metadata emitter registered"))
    }

    @Test
    fun `mixin config snapshots its generated file name`() {
        val mod = extension()
        mod.mixins.create("testmod") {
            it.packageName.set("com.example.mixin")
            it.mixin("ExampleMixin")
            it.client("ClientMixin")
        }
        val data = mod.mixins.getByName("testmod").snapshot()
        assertEquals("testmod.mixins.json", data.fileName)
        assertEquals("com.example.mixin", data.packageName)
        assertEquals(listOf("ExampleMixin"), data.mixins)
        assertEquals(listOf("ClientMixin"), data.client)
        assertEquals("0.8", data.minVersion)
        assertEquals("testmod-refmap.json", data.refmap)
    }

}
