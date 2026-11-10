package xyz.wagyourtail.unimined.remap

import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Standalone `remap.artifact()` coverage — the non-Minecraft entry point:
 * an obfuscated jar + a raw proguard mapping file, with both namespaces
 * autodetected from the input jar's class names.
 */
class StandaloneArtifactTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            it.plugins.apply(GradlePlugin::class.java)
            // pin the real engine — the fakes jar ships a second impl and
            // the implicit RemapStepSpec would be ambiguous
            it.extensions.getByType(RemapExtension::class.java)
                .jar { s -> s.remap("tiny") }
        }

    /**
     * Build a jar whose entries hold real classes under obfuscated
     * internal names — TinyRemapper keys on the class file's own name, so
     * just renaming zip entries would not exercise the remap. The obf
     * jar is produced by running the engine itself over test-classpath
     * classes.
     */
    private fun obfJar(path: Path, rename: Map<String, String>) {
        val staged = dir.resolve("staged.jar")
        JarOutputStream(Files.newOutputStream(staged)).use { jar ->
            for (src in rename.keys) {
                val bytes = javaClass
                    .getResourceAsStream("/$src.class")!!.readBytes()
                jar.putNextEntry(JarEntry("$src.class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        val remapper = TinyRemapper.newRemapper()
            .withMappings { m ->
                rename.forEach { (s, d) -> m.acceptClass(s, d) }
            }
            .skipLocalVariableMapping(true)
            .ignoreConflicts(true)
            .build()
        try {
            val tag = remapper.createInputTag()
            remapper.readInputsAsync(tag, staged).join()
            OutputConsumerPath.Builder(path).build().use { c ->
                c.addNonClassFiles(staged, remapper, emptyList())
                remapper.apply(c, tag)
            }
        } finally {
            remapper.finish()
        }
    }

    private val renames = mapOf(
        "xyz/wagyourtail/unimined/remap/fakes/FakeStep" to "c/D",
        "xyz/wagyourtail/unimined/remap/fakes/FakeRemapStep" to "c/E"
    )

    @Test
    fun `artifact remaps an obfuscated jar via a raw proguard file`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)

        // proguard direction: "source" (original) -> "target" (obfuscated)
        val mappings = dir.resolve("mappings.txt").also {
            Files.writeString(
                it,
                "com.example.Thing -> c.D:\n" +
                        "com.example.Other -> c.E:\n"
            )
        }
        val input = dir.resolve("in.jar").also { obfJar(it, renames) }
        val output = dir.resolve("out.jar")

        val task = ext.artifact("test") {
            it.input.set(input.toFile())
            it.mappings.set(mappings.toFile())
            it.output.set(output.toFile())
            // from/to intentionally unset — autodetect must resolve
            // "target" (the obf column) -> "source"
        }.get()

        task.runPipeline()

        JarFile(output.toFile()).use { jar ->
            val names = jar.entries().toList().map { it.name }
                .filter { it.endsWith(".class") }.toSet()
            assertEquals(
                setOf("com/example/Thing.class", "com/example/Other.class"),
                names
            )
        }
    }

    @Test
    fun `artifact honors explicit namespaces`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)

        val mappings = dir.resolve("mappings.txt").also {
            Files.writeString(it, "com.example.Thing -> c.D:\n")
        }
        val input = dir.resolve("in.jar").also {
            obfJar(it, mapOf(renames.keys.first() to "c/D"))
        }
        val output = dir.resolve("out.jar")

        val task = ext.artifact("test") {
            it.input.set(input.toFile())
            it.mappings.set(mappings.toFile())
            it.output.set(output.toFile())
            it.from.set("target")
            it.to.set("source")
        }.get()

        task.runPipeline()

        JarFile(output.toFile()).use { jar ->
            assertNotNull(jar.getEntry("com/example/Thing.class"))
        }
    }

    @Test
    fun `artifact reports ambiguous target namespace`() {
        val project = project()
        val ext = project.extensions.getByType(RemapExtension::class.java)

        // tiny-v2 declares three columns; autodetect finds the source but
        // cannot pick a target
        val mappings = dir.resolve("mappings.tiny").also {
            Files.writeString(
                it,
                "tiny\t2\t0\tobf\tinter\tnamed\n" +
                        "c\tc/D\tc/D\tcom/example/Thing\n"
            )
        }
        val input = dir.resolve("in.jar").also {
            obfJar(it, mapOf(renames.keys.first() to "c/D"))
        }
        val output = dir.resolve("out.jar")

        val task = ext.artifact("test") {
            it.input.set(input.toFile())
            it.mappings.set(mappings.toFile())
            it.output.set(output.toFile())
        }.get()

        val e = assertThrows(org.gradle.api.GradleException::class.java) {
            task.runPipeline()
        }
        assertTrue(e.message!!.contains("`to`"))
    }

}
