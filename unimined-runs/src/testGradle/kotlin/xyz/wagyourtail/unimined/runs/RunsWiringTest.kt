package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Verifies per-environment run wiring: default `client`/`server` run
 * configs materialize `run<Name>` tasks, the shared natives configuration
 * exists, and additional environments get their own prefixed runs.
 */
class RunsWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.runs")
        }

    @Test
    fun `main environment gets runClient and runServer`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val client = project.tasks.findByName("runClient")
        assertNotNull(client)
        assertTrue(client is MinecraftRunTask)
        assertNotNull(project.tasks.findByName("runServer"))

        val runs = project.extensions.getByType(RunsExtension::class.java)
        assertEquals("main", runs.runs.getByName("client").environment.get())
        assertEquals("main", runs.runs.getByName("server").environment.get())
        assertEquals(
            RunSide.SERVER,
            runs.runs.getByName("server").side.get()
        )
        assertNotNull(project.configurations.findByName("minecraftNatives"))
    }

    @Test
    fun `additional environments get prefixed run names`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("fabric")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
        }

        assertNotNull(project.tasks.findByName("runFabricClient"))
        assertNotNull(project.tasks.findByName("runFabricServer"))
        assertNotNull(project.configurations.findByName("minecraftFabricNatives"))
    }

    @Test
    fun `custom run config materializes a task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.create("demo") { run ->
            run.username.set("DemoDev")
            run.width.set(854)
            run.height.set(480)
        }

        val task = project.tasks.findByName("runDemo")
        assertNotNull(task)
        assertTrue(task is MinecraftRunTask)
        task as MinecraftRunTask
        assertEquals("DemoDev", task.username.get())
        assertEquals(854, task.width.get())
    }

}
