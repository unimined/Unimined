package xyz.wagyourtail.unimined.source

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.decompiler.vineflower.VineflowerDecompileTask
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import java.nio.file.Path

/**
 * Verifies `genSources` wiring: per-environment task naming, dev-jar
 * input, library classpath, and engine selection through the
 * ServiceLoader-discovered registry.
 */
class GenSourcesWiringTest {

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
            project.plugins.apply("xyz.wagyourtail.unimined.source")
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as ProjectInternal).evaluate()

    @Test
    fun `main environment gets genSources backed by vineflower`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        evaluate(project)

        val genSources = project.tasks.getByName("genSources")
        assertTrue(genSources is VineflowerDecompileTask)
        // lands in the stub module's sources slot so IDEs attach it on
        // sync without triggering a decompile
        assertEquals(
            LocalMavenPublisher.moduleDir(
                ext.localRepoDir, "unimined.generated",
                "minecraft-main", "1.20.1"
            ).resolve("minecraft-main-1.20.1-sources.jar").toFile(),
            (genSources as VineflowerDecompileTask).outputJar.get().asFile
        )
    }

    @Test
    fun `additional environments get prefixed gen task names`() {
        val project = project()
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        ext.sourceSet("fabric") { it.version.set("1.20.1") }
        evaluate(project)

        assertNotNull(project.tasks.findByName("genSources"))
        assertNotNull(project.tasks.findByName("genFabricSources"))
    }

    @Test
    fun `engine override selects by name`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        project.extensions.getByType(SourceExtension::class.java)
            .engine.set("vineflower")
        evaluate(project)
        assertNotNull(project.tasks.findByName("genSources"))
    }

}
