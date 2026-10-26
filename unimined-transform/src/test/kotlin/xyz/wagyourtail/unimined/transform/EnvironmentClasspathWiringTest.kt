package xyz.wagyourtail.unimined.transform

import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Verifies the environment↔source-set classpath wiring: stub bucket + raw/
 * dev views, library bucket extension, remapped mod views, bundle tasks,
 * and late-binding replay when the transform plugin applies after `main`
 * is already bound.
 */
class EnvironmentClasspathWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project(applyOrder: List<String>) = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            for (id in applyOrder) project.plugins.apply(id)
        }

    @Test
    fun `main environment wiring creates stub bucket, views, and bundle inputs`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))

        val minecraft = project.configurations.getByName("minecraft")
        assertEquals(false, minecraft.isCanBeResolved)
        assertEquals(false, minecraft.isCanBeConsumed)

        val libraries = project.configurations.getByName("minecraftLibraries")
        assertEquals(true, libraries.isCanBeResolved)

        val raw = project.configurations.getByName("minecraftRaw")
        assertEquals(true, raw.isCanBeResolved)
        assertEquals(listOf(minecraft), raw.extendsFrom.toList())
        assertEquals(
            "unimined-mc-raw-main",
            raw.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )

        val dev = project.configurations.getByName("minecraftDev")
        assertEquals(listOf(minecraft), dev.extendsFrom.toList())
        assertEquals(
            "unimined-mc-dev-main",
            dev.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )

        for (modView in listOf("modImplementationRemapped", "modRuntimeOnlyRemapped")) {
            val view = project.configurations.getByName(modView)
            assertEquals(
                "unimined-remapped-main-${modView.removeSuffix("Remapped")}",
                view.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
            )
        }
        assertEquals(
            listOf(project.configurations.getByName("modImplementation")),
            project.configurations.getByName("modImplementationRemapped").extendsFrom.toList()
        )

        // Transform inputs resolve the env's declared mapping sources
        // inside the bundle provider — artifact transforms cannot
        // depend on a bundle-producer task, so no produceMappingBundle_*
        // task is registered by this wiring and no eager mappings
        // configuration exists.

        assertTrue(
            project.configurations.getByName("compileClasspath")
                .extendsFrom.contains(libraries)
        )
        assertTrue(
            project.configurations.getByName("runtimeClasspath")
                .extendsFrom.contains(libraries)
        )

        assertNotNull(
            project.repositories.findByName("uniminedGenerated")
        )
    }

    @Test
    fun `dev view is registered even when transform applies after main binds`() {
        // java + minecraft first: `main` binds before transform's handler
        // registers — onEnvironmentBound must replay bound envs
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")

        assertTrue(project.configurations.findByName("minecraftDev") == null)
        project.plugins.apply("xyz.wagyourtail.unimined.remap")
        project.plugins.apply("xyz.wagyourtail.unimined.transform")
        assertNotNull(project.configurations.findByName("minecraftDev"))
    }

    @Test
    fun `named source set environments get their own wiring`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply("xyz.wagyourtail.unimined.remap")
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        project.plugins.apply("xyz.wagyourtail.unimined.transform")
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric") { it.version.set("1.20.1") }

        assertNotNull(project.configurations.findByName("fabricMinecraft"))
        assertNotNull(project.configurations.findByName("fabricMinecraftLibraries"))
        assertNotNull(project.configurations.findByName("fabricMinecraftRaw"))
        assertNotNull(project.configurations.findByName("fabricMinecraftDev"))
        assertNotNull(project.configurations.findByName("fabricModImplementationRemapped"))
        assertEquals(
            "unimined-mc-dev-fabric",
            project.configurations.getByName("fabricMinecraftDev")
                .attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )
    }

    @Test
    fun `default production wiring swaps jar classifier and registers remapJar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("dev", jar.archiveClassifier.get())

        val remapJar = project.tasks.getByName("remapJar")
            as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        // prod artifact claims the jar's original unclassified name —
        // i.e. the dev jar's name with the -dev suffix removed
        assertEquals(
            jar.archiveFileName.get().replace("-dev.", "."),
            remapJar.outputArtifact.get().asFile.name
        )
        val buildDeps = project.tasks.getByName("build")
            .taskDependencies.getDependencies(null)
        assertTrue(buildDeps.any { it.name == "remapJar" })
    }

    @Test
    fun `defaultRemapJar false skips production wiring`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.defaultRemapJar.set(false)
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("", jar.archiveClassifier.get())
        assertTrue(project.tasks.findByName("remapJar") == null)
    }

    @Test
    fun `user archive classifier gains a dev suffix for the dev jar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        project.tasks.named("jar", org.gradle.jvm.tasks.Jar::class.java) {
            it.archiveClassifier.set("shadow")
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("shadow-dev", jar.archiveClassifier.get())
        val remapJar = project.tasks.getByName("remapJar")
            as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertTrue(remapJar.outputArtifact.get().asFile.name.endsWith("-shadow.jar"))
    }

}
