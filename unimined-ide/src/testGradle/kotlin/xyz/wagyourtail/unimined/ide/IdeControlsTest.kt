package xyz.wagyourtail.unimined.ide

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.ide.internal.IdeWiring
import xyz.wagyourtail.unimined.runs.RunConfig

/** `ideConfigGenerated`/`ideName`/`ideFolderName` per-run IDE
 *  controls — the emission filter predicate and the model fields. */
class IdeControlsTest {

    private fun project() = ProjectBuilder.builder().build()

    private fun run(project: org.gradle.api.Project, name: String) =
        project.objects.newInstance(RunConfig::class.java, name)

    @Test
    fun `ideConfigGenerated false suppresses the run`() {
        val project = project()
        val run = run(project, "client")
        // env convention "main" + generated convention true
        assertTrue(IdeWiring.includeRun(project, run, "main"))
        run.ideConfigGenerated.set(false)
        assertFalse(IdeWiring.includeRun(project, run, "main"),
            "opt-out should drop the run before model resolution")
    }

    @Test
    fun `env binding still gates inclusion`() {
        val project = project()
        val run = run(project, "fabricClient")
        run.environment.set("fabric")
        assertFalse(IdeWiring.includeRun(project, run, "main"))
        assertTrue(IdeWiring.includeRun(project, run, "fabric"))
    }

    @Test
    fun `ideName and ideFolderName carry through the model`() {
        val run = run(project(), "client")
        // conventions: name for ideName, absent folder
        assertEquals("client", run.ideName.get())
        assertFalse(run.ideFolderName.isPresent)
        run.ideName.set("Debug Client")
        run.ideFolderName.set("debug")
        val model = IdeRunModel(
            name = run.ideName.get(),
            folderName = run.ideFolderName.orNull,
            environmentId = "main",
            side = "CLIENT",
            prod = false,
            mainClass = "x.Main",
            jvmArgs = emptyList(),
            programArgs = emptyList(),
            environmentVariables = emptyMap(),
            workingDirectory = "run",
            classpath = emptyList(),
        )
        assertEquals("Debug Client", model.name)
        assertEquals("debug", model.folderName)
    }
}
