package xyz.wagyourtail.unimined.mod

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mod.task.MergeEraJarsTask
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText

class MergeEraJarsTaskTest {

    @TempDir
    lateinit var dir: Path

    private fun zip(path: Path, entries: Map<String, String>, manifest: String? = null) {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun task(): MergeEraJarsTask {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        val task = project.tasks.register("mergeTest", MergeEraJarsTask::class.java).get()
        task.destinationDirectory.set(dir.resolve("out").toFile())
        task.archiveBaseName.set("merged")
        task.archiveVersion.set("1.0")
        task.archiveClassifier.set("test")
        task.archiveFileName.set("merged-1.0-test.jar")
        task.archiveFile.fileValue(dir.resolve("out/merged-1.0-test.jar").toFile())
        return task
    }

    private fun configureFixture(task: MergeEraJarsTask): Pair<Path, Path> {
        val shared = dir.resolve("shared")
        Files.createDirectories(shared.resolve("com/example"))
        Files.writeString(shared.resolve("com/example/Main.class"), "main")
        Files.writeString(shared.resolve("overlap.txt"), "main-wins")
        val fabric = dir.resolve("fabric.jar")
        zip(
            fabric,
            mapOf(
                "fabric.mod.json" to "{\"id\":\"era\"}",
                "com/example/Fabric.class" to "fabric",
                "overlap.txt" to "fabric-loses"
            ),
            "Manifest-Version: 1.0\r\nNested-Attr: nested-input\r\n\r\n"
        )
        val forge = dir.resolve("forge.jar")
        zip(
            forge,
            mapOf(
                "com/example/Forge.class" to "forge",
                "overlap.txt" to "forge-loses"
            ),
            "Manifest-Version: 1.0\r\nOuter-Attr: outer-input\r\nOverride-Me: input\r\n\r\n"
        )
        task.sharedOutputs.from(shared.toFile())
        task.sharedOutputPaths.set(listOf(shared.toAbsolutePath().toString()))
        task.eraJarFiles.from(fabric.toFile(), forge.toFile())
        task.eraInputs.set(listOf(
            EraJarInput("fabric", "nest", fabric.toAbsolutePath().toString()),
            EraJarInput("forge", "flatten", forge.toAbsolutePath().toString())
        ))
        task.nestedId.set("test-inner")
        task.nestedParent.set("test")
        task.nestedVersion.set("1.0")
        task.nestedEntries.set(listOf("META-INF/mm.jar"))
        val mm = dir.resolve("mm.bin")
        Files.writeString(mm, "mm")
        task.additionalNestedJarFiles.from(mm.toFile())
        task.additionalNestedInputs.set(listOf(
            AdditionalNestedJarInput("META-INF/mm.jar", listOf(mm.toAbsolutePath().toString()))
        ))
        task.manifestAttributes.put("Override-Me", "spec")
        return fabric to forge
    }

    @Test
    fun `nested and flattened entries merge main-first with shell descriptor`() {
        val task = task()
        configureFixture(task)
        task.merge()

        val output = dir.resolve("out/merged-1.0-test.jar").toFile()
        JarFile(output).use { outer ->
            assertEquals("main", String(outer.getInputStream(outer.getEntry("com/example/Main.class")).readBytes()))
            assertEquals("main-wins", String(outer.getInputStream(outer.getEntry("overlap.txt")).readBytes()))
            assertNotNull(outer.getEntry("com/example/Forge.class"))
            assertNull(outer.getEntry("com/example/Fabric.class"))
            assertEquals("outer-input", outer.manifest.mainAttributes.getValue("Outer-Attr"))
            assertEquals("spec", outer.manifest.mainAttributes.getValue("Override-Me"))
            val nested = outer.getEntry("META-INF/jars/nested.jar")
            assertNotNull(nested)
            JarFile(dir.resolve("nested-copy.jar").also {
                Files.write(it, outer.getInputStream(nested).readBytes())
            }.toFile()).use { inner ->
                assertNotNull(inner.getEntry("com/example/Fabric.class"))
                assertNull(inner.getEntry("overlap.txt"))
                assertNotNull(inner.getEntry("META-INF/mm.jar"))
                val fmj = String(inner.getInputStream(inner.getEntry("fabric.mod.json")).readBytes())
                assertTrue(fmj.contains("\"id\":\"test-inner\""), fmj)
                assertTrue(fmj.contains("\"parent\":{\"id\":\"test\"}"), fmj)
                assertTrue(fmj.contains("\"file\":\"META-INF/mm.jar\""), fmj)
                assertEquals("nested-input", inner.manifest.mainAttributes.getValue("Nested-Attr"))
            }
        }
    }

    @Test
    fun `strict overlap validation fails before writing`() {
        val task = task()
        configureFixture(task)
        task.overlapValidation.set("strict")
        val output = dir.resolve("out/merged-1.0-test.jar")
        val ex = assertThrows(GradleException::class.java) { task.merge() }
        assertTrue(ex.message!!.contains("overlap.txt"), ex.message)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `invalid stringly values fail with valid choices`() {
        val task = task()
        task.overlapValidation.set("sometimes")
        val ex = assertThrows(GradleException::class.java) { task.merge() }
        assertTrue(ex.message!!.contains("warn, strict, off"), ex.message)
    }
}
