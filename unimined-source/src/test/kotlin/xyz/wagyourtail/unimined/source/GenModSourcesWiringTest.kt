package xyz.wagyourtail.unimined.source

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.source.task.GenModSourcesTask
import xyz.wagyourtail.unimined.source.task.ModSourceSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies `genModSources` wiring: per-environment task naming, the
 * decompile transform-chain views over mod buckets, the SOURCES-surface
 * chained type, and the publish action's repository layout.
 */
class GenModSourcesWiringTest {

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

    private fun evaluate(project: Project) = (project as ProjectInternal).evaluate()

    private fun viewArtifactType(project: Project, name: String): String? =
        project.configurations.findByName(name)
            ?.attributes?.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)

    @Test
    fun `main environment gets genModSources and per-bucket decompile views`() {
        val project = project()
        project.extensions.getByType(MinecraftExtension::class.java)
            .sourceSet("main") { it.version.set("1.20.1") }
        evaluate(project)

        assertNotNull(project.tasks.findByName("genModSources"))
        assertTrue(
            project.tasks.getByName("genModSources") is GenModSourcesTask
        )
        // without SOURCES-surface steps the decompiled type is final
        assertEquals(
            "unimined-modsrc-decompiled-main-modImplementation",
            viewArtifactType(project, "modImplementationModSources")
        )
        assertEquals(
            "unimined-modsrc-decompiled-main-modRuntimeOnly",
            viewArtifactType(project, "modRuntimeOnlyModSources")
        )
    }

    @Test
    fun `additional environments get prefixed task and view names`() {
        val project = project()
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        ext.sourceSet("fabric") { it.version.set("1.20.1") }
        evaluate(project)

        assertNotNull(project.tasks.findByName("genModSources"))
        assertNotNull(project.tasks.findByName("genFabricModSources"))
        assertNotNull(
            project.configurations.findByName("fabricModImplementationModSources")
        )
    }

    @Test
    fun `sources-surface steps chain a second transform onto the view`() {
        val project = project()
        project.extensions.getByType(MinecraftExtension::class.java)
            .sourceSet("main") { it.version.set("1.20.1") }
        project.extensions.getByType(RemapExtension::class.java)
            .sources(Action { it.remap("uml") })
        evaluate(project)

        assertEquals(
            "unimined-modsrc-main-modImplementation",
            viewArtifactType(project, "modImplementationModSources")
        )
    }

    @Test
    fun `publish writes the sources classifier under original coordinates`() {
        val project = project()
        val repoDir = dir.resolve("repo")
        val jar = Files.createFile(dir.resolve("mod-1.0-named-sources.jar"))

        val task = project.tasks.register(
            "publishTestSources", GenModSourcesTask::class.java
        ) { t ->
            t.sourceJars.from(project.files(jar))
            t.mods.set(
                listOf(ModSourceSpec("net.fabricmc", "mod", "1.0", jar.fileName.toString()))
            )
            t.localRepoDir.fileValue(repoDir.toFile())
        }.get()
        task.publish()

        val moduleDir = repoDir.resolve("net/fabricmc/mod/1.0")
        assertTrue(moduleDir.resolve("mod-1.0-sources.jar").toFile().exists())
        // no POM — a descriptor under real coordinates would shadow the
        // module's real metadata when this repo is consulted first
        assertNull(moduleDir.resolve("mod-1.0.pom").toFile().takeIf { it.exists() })
        assertEquals(
            listOf(moduleDir.resolve("mod-1.0-sources.jar")),
            task.publishedSources.get().map { it.asFile.toPath() }
        )
    }

}
