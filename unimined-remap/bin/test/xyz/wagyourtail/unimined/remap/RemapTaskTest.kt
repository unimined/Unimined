package xyz.wagyourtail.unimined.remap

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.remap.fakes.FakeStep
import xyz.wagyourtail.unimined.remap.fakes.FakeStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.task.RemapJarTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class RemapTaskTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { it.plugins.apply(GradlePlugin::class.java) }

    private fun writeJar(path: Path, entry: String) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry(entry))
            jar.write(byteArrayOf(1, 2, 3))
            jar.closeEntry()
        }
    }

    @BeforeEach
    fun resetFake() = FakeStep.reset()

    @Test
    fun `task runs the declared pipeline and copies output`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)
        ext.jar { it.step(FakeStepSpec()) }

        val input = dir.resolve("in.jar").also { writeJar(it, "original.bin") }
        val output = dir.resolve("out.jar")

        val task = project.tasks.create("remapJar", RemapJarTask::class.java)
        task.inputArtifact.set(input.toFile())
        task.outputArtifact.set(output.toFile())
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.stepSpecs.set(ext.stepSpecs(RemapSurface.JAR))

        task.runPipeline()

        assertEquals(listOf(input), FakeStep.calls)
        JarFile(output.toFile()).use { jar ->
            assertNotNull(jar.getEntry("original.bin"))
            assertNotNull(jar.getEntry(FakeStep.MARKER))
        }
    }

    @Test
    fun `empty pipeline copies input to output`() {
        val project = project()
        val input = dir.resolve("in.bin").also { Files.write(it, byteArrayOf(9)) }
        val output = dir.resolve("out.bin")

        val task = project.tasks.create("remapJar", RemapJarTask::class.java)
        task.inputArtifact.set(input.toFile())
        task.outputArtifact.set(output.toFile())
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.stepSpecs.set(emptyList())

        task.runPipeline()

        assertTrue(Files.exists(output))
        assertEquals(9, Files.readAllBytes(output).single())
    }

    @Test
    fun `engine discovery picks up classpath service impls`() {
        val registry = RemapStepRegistry()
        val spec = object : xyz.wagyourtail.unimined.api.spec.RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath = emptyList<org.gradle.api.file.FileSystemLocation>()
        }
        val type = registry.resolve(FakeStepSpec(), RemapSurface.JAR, spec)
        assertEquals("fake", type.name)
    }

}
