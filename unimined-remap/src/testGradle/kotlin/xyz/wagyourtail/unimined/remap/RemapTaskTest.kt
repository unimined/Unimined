package xyz.wagyourtail.unimined.remap

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
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

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, data) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(data)
                jar.closeEntry()
            }
        }
    }

    private fun remapJarTask(
        project: org.gradle.api.Project, input: Path,
        surface: RemapSurface = RemapSurface.JAR
    ): RemapJarTask {
        val task = project.tasks.register("remapJar", RemapJarTask::class.java).get()
        task.from(project.zipTree(input))
        task.destinationDirectory.set(dir.toFile())
        task.archiveFileName.set("out.jar")
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.surface.set(surface)
        task.stepSpecs.set(emptyList())
        return task
    }

    @BeforeEach
    fun resetFake() = FakeStep.reset()

    @Test
    fun `task runs the declared pipeline and copies output`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)
        ext.jar { it.step(FakeStepSpec()) }

        val input = dir.resolve("in.jar").also { writeJar(it, "original.bin") }

        val task = project.tasks.register("remapJar", RemapJarTask::class.java).get()
        // the task mirrors a jar spec — feed it the input's contents
        task.from(project.zipTree(input))
        task.destinationDirectory.set(dir.toFile())
        task.archiveFileName.set("out.jar")
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.surface.set(RemapSurface.JAR)
        task.stepSpecs.set(ext.stepSpecs(RemapSurface.JAR))

        task.copy()

        assertEquals(1, FakeStep.calls.size)
        JarFile(dir.resolve("out.jar").toFile()).use { jar ->
            assertNotNull(jar.getEntry("original.bin"))
            assertNotNull(jar.getEntry(FakeStep.MARKER))
        }
    }

    @Test
    fun `task feeds the serialized access model to needing steps`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)
        ext.jar { it.step(FakeStepSpec()) }

        val input = dir.resolve("in.jar").also { writeJar(it, "original.bin") }
        val model = xyz.wagyourtail.unimined.api.access.AccessModel(
            listOf(
                xyz.wagyourtail.unimined.api.access.AccessModel.Section(
                    "official",
                    listOf(
                        xyz.wagyourtail.unimined.api.access.AccessModel.Entry(
                            xyz.wagyourtail.unimined.api.access.AccessModel.Kind.METHOD,
                            "a/A", "m", "()V",
                            xyz.wagyourtail.unimined.api.access.AccessModel.Change(0x0001, 7)
                        )
                    )
                )
            )
        )

        val task = project.tasks.register("remapJar", RemapJarTask::class.java).get()
        task.from(project.zipTree(input))
        task.destinationDirectory.set(dir.toFile())
        task.archiveFileName.set("out.jar")
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.surface.set(RemapSurface.JAR)
        task.stepSpecs.set(ext.stepSpecs(RemapSurface.JAR))
        task.accessModel.set(model.serialize())

        task.copy()

        val seen = FakeStep.accessModels.single()
        assertNotNull(seen)
        assertEquals(0x0001, seen!!.rules("official").methodChange("a/A", "m", "()V")?.set)
    }

    @Test
    fun `empty pipeline copies input to output`() {
        val project = project()
        val input = dir.resolve("in.bin").also { Files.write(it, byteArrayOf(9)) }
        val output = dir.resolve("out.jar")

        val task = project.tasks.register("remapJar", RemapJarTask::class.java).get()
        task.from(input)
        task.destinationDirectory.set(dir.toFile())
        task.archiveFileName.set("out.jar")
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.surface.set(RemapSurface.JAR)
        task.stepSpecs.set(emptyList())

        task.copy()

        assertTrue(Files.exists(output))
        JarFile(output.toFile()).use { jar ->
            assertEquals(9, jar.getInputStream(jar.getEntry("in.bin")).readBytes().single())
        }
    }

    @Test
    fun `production jar carries the namespace marker`() {
        val project = project()
        val input = dir.resolve("in.jar").also { writeJar(it, "original.bin") }
        val task = remapJarTask(project, input)
        task.copy()
        JarFile(dir.resolve("out.jar").toFile()).use { jar ->
            assertEquals(
                "named",
                jar.manifest.mainAttributes.getValue(
                    xyz.wagyourtail.unimined.remap.internal.JarLint.NAMESPACE_ATTRIBUTE
                )
            )
        }
    }

    @Test
    fun `footgunChecks error fails on dangling access widener`() {
        val project = project()
        val input = dir.resolve("in.jar").also {
            writeJar(it, mapOf(
                "fabric.mod.json" to """{"id": "m", "version": "1",
                    "accessWidener": "missing.accesswidener"}""".toByteArray()
            ))
        }
        val task = remapJarTask(project, input)
        val e = assertThrows(org.gradle.api.GradleException::class.java) {
            task.copy()
        }
        assertTrue(e.message!!.contains("missing.accesswidener"))
    }

    @Test
    fun `footgunChecks warn logs but succeeds`() {
        val project = project()
        val input = dir.resolve("in.jar").also {
            writeJar(it, mapOf(
                "fabric.mod.json" to """{"id": "m", "version": "1",
                    "accessWidener": "missing.accesswidener"}""".toByteArray()
            ))
        }
        val task = remapJarTask(project, input)
        task.footgunChecks.set(FootgunCheckLevel.WARN)
        task.copy()
        assertTrue(Files.exists(dir.resolve("out.jar")))
    }

    @Test
    fun `footgunChecks off skips validation`() {
        val project = project()
        val input = dir.resolve("in.jar").also {
            writeJar(it, mapOf(
                "fabric.mod.json" to """{"id": "m", "version": "1",
                    "accessWidener": "missing.accesswidener"}""".toByteArray()
            ))
        }
        val task = remapJarTask(project, input)
        task.footgunChecks.set(FootgunCheckLevel.OFF)
        task.copy()
        assertTrue(Files.exists(dir.resolve("out.jar")))
    }

    @Test
    fun `sources surface gets the marker but skips metadata lint`() {
        val project = project()
        val input = dir.resolve("in.jar").also {
            writeJar(it, mapOf(
                "fabric.mod.json" to """{"id": "m", "version": "1",
                    "accessWidener": "missing.accesswidener"}""".toByteArray()
            ))
        }
        val task = remapJarTask(project, input, RemapSurface.SOURCES)
        // dangling AW in a sources jar: no class-path lint, no failure
        task.copy()
        JarFile(dir.resolve("out.jar").toFile()).use { jar ->
            assertEquals(
                "named",
                jar.manifest.mainAttributes.getValue(
                    xyz.wagyourtail.unimined.remap.internal.JarLint.NAMESPACE_ATTRIBUTE
                )
            )
        }
    }

    @Test
    fun `engine discovery picks up classpath service impls`() {
        val registry = RemapStepRegistry()
        val spec = object : xyz.wagyourtail.unimined.api.spec.RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath = emptyList<java.nio.file.Path>()
        }
        val type = registry.resolve(FakeStepSpec(), RemapSurface.JAR, spec)
        assertEquals("fake", type.name)
    }

}
