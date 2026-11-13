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

    @Test
    fun `declared and mutator javaagents reach the run task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val agentFile = dir.resolve("agent.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.runs.getByName("client").javaagents.from(agentFile)

        // transform-mutator path: specs add typed paths, wiring maps
        // them onto `mutatedJavaagents` for -javaagent: rendering
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.javaagents.add(dir.resolve("mutator-agent.jar"))
        }

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertTrue(task.javaagents.files.contains(agentFile))
        assertTrue(
            dir.resolve("mutator-agent.jar").toString() in
                task.mutatedJavaagents.get())
    }

    @Test
    fun `prepare task stages modFiles and renamed dynLibFiles`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val task = project.tasks.findByName("prepareRunClient")
        assertNotNull(task)
        task as PrepareRunTask

        val runDir = dir.resolve("run-client")
        task.runDirectory.set(runDir.toFile())

        val mod = dir.resolve("mod.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        val lib = dir.resolve("asm-all-5.2.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        task.modFiles.from(mod)
        task.stageInto.set("mods")
        task.dynLibFiles.from(lib)
        task.dynLibInto.set("lib")
        task.dynLibRenames.put("asm-all-5.2.jar", "asm-all-4.1.jar")

        task.prepare()

        assertTrue(runDir.resolve("mods/mod.jar").toFile().isFile)
        assertTrue(runDir.resolve("lib/asm-all-4.1.jar").toFile().isFile)
        assertFalse(runDir.resolve("lib/asm-all-5.2.jar").toFile().exists())
        // mods channel must not leak into the lib dir
        assertFalse(runDir.resolve("lib/mod.jar").toFile().exists())
    }

    @Test
    fun `prodClient and prodServer materialize prod run tasks`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        assertTrue(
            project.tasks.findByName("runProdClient") is MinecraftRunTask)
        assertTrue(
            project.tasks.findByName("runProdServer") is MinecraftRunTask)

        val runs = project.extensions.getByType(RunsExtension::class.java)
        assertTrue(runs.runs.getByName("prodClient").prod.get())
        assertTrue(runs.runs.getByName("prodServer").prod.get())
        assertEquals("main", runs.runs.getByName("prodClient").environment.get())
    }

    @Test
    fun `prod run classpath resolves the env production MC view`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val prodConf = project.configurations.getByName("minecraftProd")
        val task =
            project.tasks.findByName("runProdClient") as MinecraftRunTask
        assertTrue(
            task.runtimeClasspath.from.any {
                (it as? org.gradle.api.NamedDomainObjectProvider<*>)
                    ?.orNull === prodConf
            },
            "runProdClient classpath should carry the minecraftProd view"
        )
    }

    @Test
    fun `loader override swaps the prod run MC view`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
        }
        // FG2-era forge wiring points prod runs at the raw artifact +
        // FML's runtime deobf — the prod view would skip the
        // authentic obf→srg launch path
        env.prodRunViewName = "minecraftRaw"

        val rawConf = project.configurations.getByName("minecraftRaw")
        val task =
            project.tasks.findByName("runProdClient") as MinecraftRunTask
        assertTrue(
            task.runtimeClasspath.from.any {
                (it as? org.gradle.api.NamedDomainObjectProvider<*>)
                    ?.orNull === rawConf
            },
            "prodRunViewName override should route the prod classpath"
        )
    }

}
