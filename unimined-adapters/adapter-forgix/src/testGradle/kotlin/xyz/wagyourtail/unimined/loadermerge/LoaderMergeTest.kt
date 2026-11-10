package xyz.wagyourtail.unimined.loadermerge

import io.github.pacifistmc.forgix.plugin.ForgixGradlePlugin
import io.github.pacifistmc.forgix.plugin.configurations.ForgixConfiguration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.loadermerge.task.MergeLoaderJarsTask
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * The adapter drives Forgix's engine (`Forgix.mergeLoaders`) from
 * `mergeLoaderJars`, fed by bound environments, the upstream `forgix {}`
 * DSL's `mergeConfigurations`, and explicit `loaderMerge.from(...)`.
 */
class LoaderMergeTest {

    private fun project(dir: File? = null) = (
            if (dir != null) ProjectBuilder.builder().withProjectDir(dir)
            else ProjectBuilder.builder()
            ).build().also {
            it.pluginManager.apply("java")
            it.pluginManager.apply(
                xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
            it.plugins.apply(GradlePlugin::class.java)
        }

    @Test
    fun `bound environment jars feed the merge by default`() {
        val project = project()

        // no explicit from() — the bound `main` env is discovered under
        // its loader name (env id when no loaderSpec is set)
        val task = project.tasks.getByName("mergeLoaderJars") as MergeLoaderJarsTask
        assertTrue(
            task.inputNames.get().contains("main"),
            "bound env auto-discovered: ${task.inputNames.get()}"
        )
    }

    @Test
    fun `aux environment is not fed`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        // aux flag set before the adapter wires — the bound handler
        // replays and must see it
        project.extensions.getByType(
            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java
        ).main.isAuxEnv.set(true)
        project.plugins.apply(GradlePlugin::class.java)

        val task = project.tasks.getByName("mergeLoaderJars") as MergeLoaderJarsTask
        assertTrue(task.inputNames.get().isEmpty(),
            "aux env must not be fed: ${task.inputNames.get()}")
    }

    @Test
    fun `forgix DSL configurations feed the merge`() {
        val project = project()
        project.pluginManager.apply(ForgixGradlePlugin::class.java)

        // upstream merge() can't express non-subproject names (validates
        // inputJar before running the action) — insert the way the
        // adapter's docs describe
        val forgix = project.rootProject.extensions
            .getByType(ForgixConfiguration::class.java)
        val cfg = project.objects.newInstance(
            ForgixConfiguration.MergeLoaderConfiguration::class.java)
        cfg.inputJar.set(project.layout.file(
            project.provider { project.rootDir.resolve("custom.jar") }))
        forgix.mergeConfigurations["custom"] = cfg

        val task = project.tasks.getByName("mergeLoaderJars") as MergeLoaderJarsTask
        val names = task.inputNames.get()
        assertTrue("main" in names && "custom" in names,
            "env + forgix DSL union: $names")
    }

    @Test
    fun `merged artifact is selectable`(@TempDir dir: File) {
        // bare project — env feed off, explicit inputs only
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.plugins.apply(GradlePlugin::class.java)

        writeJar(dir.resolve("forge.jar"), "fg".toByteArray())
        writeJar(dir.resolve("fabric.jar"), "fb".toByteArray())

        val ext = project.extensions.getByType(LoaderMergeExtension::class.java)
        ext.input("forge", dir.resolve("forge.jar"))
        ext.input("fabric", dir.resolve("fabric.jar"))

        val task = project.tasks.getByName("mergeLoaderJars") as MergeLoaderJarsTask
        task.merge()

        val merged = File(dir, "build/libs/${project.name}-merged.jar")
        assertTrue(merged.exists() && merged.length() > 0,
            "merged jar written at $merged")

        // independently selectable: registered on mergedElements
        val artifacts = project.configurations.getByName("mergedElements")
            .outgoing.artifacts.files.files
        assertTrue(
            artifacts.any { it.name.endsWith("-merged.jar") },
            "merged jar published on mergedElements: $artifacts"
        )
        assertEquals("merged",
            project.configurations.getByName("mergedElements")
                .outgoing.artifacts.first().classifier)
    }

    private fun writeJar(f: File, content: ByteArray) {
        JarOutputStream(f.outputStream()).use {
            it.putNextEntry(JarEntry("x.bin")); it.write(content); it.closeEntry()
        }
    }

}
