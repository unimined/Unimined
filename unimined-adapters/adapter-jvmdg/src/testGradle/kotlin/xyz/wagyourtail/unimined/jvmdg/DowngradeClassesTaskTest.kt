package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import xyz.wagyourtail.unimined.jvmdg.task.DowngradeClassesTask
import java.nio.file.Path

/**
 * Gate semantics of [DowngradeClassesTask]: the run floor
 * ([DowngradeClassesTask.requiredJava]) bounds the emit target, and
 * the *compile* level ([DowngradeClassesTask.compileJava]) — which
 * `jvmdowngrader.source` may raise above the run floor — decides
 * whether there is anything to downgrade.
 */
class DowngradeClassesTaskTest {

    @TempDir
    lateinit var dir: Path

    private fun task(): DowngradeClassesTask {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("proj").toFile().apply { mkdirs() })
            .build()
        return project.tasks.register(
            "downgradeTestClasses", DowngradeClassesTask::class.java
        ).get()
    }

    /** A stub-free api jar — the published java-api jar carries stubs
     *  compiled past this suite's Java 17 toolchain (j21 stubs are
     *  v64), and jvmdg enumerates them eagerly during path downgrades;
     *  an empty jar still satisfies `Flags.api` so the run never falls
     *  back to jvmdg's network auto-download. Stub shading itself is
     *  covered by JvmDowngradeStepTypeTest. */
    private fun javaApiJar(): java.io.File {
        val jar = dir.resolve("api.jar").toFile()
        java.util.jar.JarOutputStream(jar.outputStream()).use { }
        return jar
    }

    /** A class dir holding one class at the given class-file major —
     *  path mirrors the internal name (jvmdg rejects mismatches). */
    private fun classDir(classFileVersion: Int): Path {
        val classes = dir.resolve("classes-v$classFileVersion")
        val pkg = classes.resolve("example").apply {
            Files.createDirectories(this) }
        val writer = ClassWriter(0)
        writer.visit(
            classFileVersion, Opcodes.ACC_PUBLIC, "example/Thing",
            null, "java/lang/Object", null
        )
        writer.visitEnd()
        Files.write(pkg.resolve("Thing.class"), writer.toByteArray())
        return classes
    }

    private fun classVersion(path: Path): Int {
        val bytes = Files.readAllBytes(path)
        return (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
    }

    @Test
    fun `disabled is a no-op`() {
        val task = task()
        val out = dir.resolve("out")
        task.downgradeEnabled.set(false)
        task.requiredJava.set(8)
        task.compileJava.set(21)
        task.downgradeTo.set(JavaVersion.VERSION_1_8)
        task.inputDirs.from(classDir(Opcodes.V21))
        task.outputDir.set(out.toFile())
        task.downgrade()
        assertFalse(out.toFile().exists())
    }

    @Test
    fun `target above the run floor fails`() {
        val task = task()
        task.downgradeEnabled.set(true)
        task.requiredJava.set(8)
        task.compileJava.set(21)
        task.downgradeTo.set(JavaVersion.VERSION_11) // target 11 > run floor 8
        task.outputDir.set(dir.resolve("out").toFile())
        val e = assertThrows<GradleException> { task.downgrade() }
        assertTrue(e.message!!.contains("run JVM floor"))
    }

    @Test
    fun `compile at target passes through without writing`() {
        // enabled and target valid — but javac already emitted at the
        // era level (no `source` raise): nothing to downgrade
        val task = task()
        val out = dir.resolve("out")
        task.downgradeEnabled.set(true)
        task.requiredJava.set(8)
        task.compileJava.set(8)
        task.downgradeTo.set(JavaVersion.VERSION_1_8)
        task.inputDirs.from(classDir(Opcodes.V1_8))
        task.apiJar.set(listOf(javaApiJar()))
        task.outputDir.set(out.toFile())
        task.downgrade()
        assertFalse(out.toFile().exists())
    }

    @Test
    fun `compile above target emits downgraded mirrors`() {
        // `jvmdowngrader { source.set(17); target.set(8) }`: javac v61
        // in, v52 mirror + downgraded api jar out. v61 is the newest
        // input this suite's Java 17 test JVM can reflect on (jvmdg
        // loads input classes for member resolution)
        val task = task()
        val out = dir.resolve("out")
        task.downgradeEnabled.set(true)
        task.requiredJava.set(8)
        task.compileJava.set(17)
        task.downgradeTo.set(JavaVersion.VERSION_1_8)
        task.inputDirs.from(classDir(Opcodes.V17))
        task.apiJar.set(listOf(javaApiJar()))
        task.outputDir.set(out.toFile())
        task.downgrade()
        assertEquals(52, classVersion(
            out.resolve("0").resolve("example").resolve("Thing.class")))
        assertTrue(out.resolve("api-0.jar").toFile().isFile)
    }

}
