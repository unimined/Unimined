package xyz.wagyourtail.unimined.loadermerge

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.loadermerge.task.MergeLoaderJarsTask
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Acceptance test per plan §12: fake per-loader jars through a fake
 * merger adapter; the merged output must be independently selectable
 * (registered on `mergedElements` with the `merged` classifier).
 */
class LoaderMergeTest {

    /** Fake merger — writes per-loader marker entries so we can verify. */
    class FakeMerger : ModLoaderMerger {
        override val name = "fake"
        override fun merge(inputs: Map<String, Path>, output: Path) {
            JarOutputStream(output.toFile().outputStream()).use { jar ->
                for ((name, jarPath) in inputs.toSortedMap()) {
                    jar.putNextEntry(JarEntry("loader/$name.marker"))
                    jar.write(jarPath.toFile().length().toString().toByteArray())
                    jar.closeEntry()
                }
            }
        }
    }

    @Test
    fun `merged artifact is selectable`(@TempDir dir: File) {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir).build()
        project.plugins.apply(GradlePlugin::class.java)

        writeJar(dir.resolve("forge.jar"), "fg".toByteArray())
        writeJar(dir.resolve("fabric.jar"), "fb".toByteArray())

        val ext = project.extensions.getByType(LoaderMergeExtension::class.java)
        ext.engine.set("fake")
        ext.input("forge", dir.resolve("forge.jar"))
        ext.input("fabric", dir.resolve("fabric.jar"))

        val task = project.tasks.getByName("mergeLoaderJars") as MergeLoaderJarsTask
        task.merge()

        val merged = File(dir, "build/libs/${project.name}-merged.jar")
        assertTrue(merged.exists(), "merged jar written at $merged")
        JarFile(merged).use {
            assertNotNull(it.getJarEntry("loader/forge.marker"))
            assertNotNull(it.getJarEntry("loader/fabric.marker"))
        }

        // independently selectable: registered on mergedElements
        val artifacts = project.configurations.getByName("mergedElements")
            .outgoing.artifacts.files.files
        assertTrue(
            artifacts.any { it.name.endsWith("-merged.jar") },
            "merged jar published on mergedElements: $artifacts"
        )
    }

    private fun writeJar(f: File, content: ByteArray) {
        JarOutputStream(f.outputStream()).use {
            it.putNextEntry(JarEntry("x.bin")); it.write(content); it.closeEntry()
        }
    }

}
