package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class JvmDowngradeStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects

    private val spec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "official"
        override val classpath: List<Path> = emptyList()
    }

    private fun ctx(): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
        override val spec = this@JvmDowngradeStepTypeTest.spec
        override val mappings = null
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = uniminedLogger(Logging.getLogger(JvmDowngradeStepTypeTest::class.java))
    }

    /** Resolve the published java-api jar off the test classpath so the
     *  run never falls back to jvmdg's network auto-download. */
    private fun javaApiJar(): java.io.File =
        System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { java.io.File(it) }
            .filter { it.name.startsWith("jvmdowngrader-java-api") && it.extension == "jar" }
            // stale api jars can linger on the classpath — take the
            // newest (1.x api jars break 2.x ApiShader)
            .maxByOrNull { it.name }!!

    /** A jar holding one class at the given class-file version. */
    private fun inputJar(classFileVersion: Int, withModernApiCall: Boolean = false): Path {
        val jar = dir.resolve("in-v$classFileVersion.jar")
        val writer = ClassWriter(0)
        writer.visit(
            classFileVersion, Opcodes.ACC_PUBLIC, "example/Thing",
            null, "java/lang/Object", null
        )
        if (withModernApiCall) {
            // `String.isBlank()` — Java 11+: the downgrade must rewrite
            // it to a java-api stub, which shading then inlines
            val m = writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "check", "()Z",
                null, null)
            m.visitCode()
            m.visitLdcInsn("x")
            m.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "isBlank",
                "()Z", false)
            m.visitInsn(Opcodes.IRETURN)
            m.visitMaxs(1, 0)
            m.visitEnd()
        }
        writer.visitEnd()
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("example/Thing.class"))
            out.write(writer.toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("resource.txt"))
            out.write("kept".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    private fun classVersion(path: Path): Int =
        java.util.jar.JarFile(path.toFile()).use { jar ->
            val bytes = jar.getInputStream(jar.getEntry("example/Thing.class")).readBytes()
            // class-file major: big-endian u2 at offset 6
            (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
        }

    @Test
    fun `registry discovers jvmdg via ServiceLoader`() {
        val type = RemapStepRegistry().resolve(
            objects.newInstance(JvmDowngradeStepSpec::class.java),
            RemapSurface.JAR, spec
        )
        assertEquals("jvmdg", type.name)
        assertEquals("downgrade", type.stepName)
    }

    @Test
    fun `downgrades a v61 class to v52`() {
        val stepSpec = objects.newInstance(JvmDowngradeStepSpec::class.java).apply {
            target.set(JavaVersion.VERSION_1_8)
            api.from(javaApiJar())
        }
        val ctx = ctx()
        val out = JvmDowngradeStepType().create(stepSpec, ctx)
            .apply(inputJar(Opcodes.V17), ctx)

        assertEquals(52, classVersion(out))
        java.util.jar.JarFile(out.toFile()).use { jar ->
            assertEquals("kept", jar.getInputStream(jar.getEntry("resource.txt"))
                .bufferedReader().readText())
        }
    }

    @Test
    fun `classVersion overrides target`() {
        val stepSpec = objects.newInstance(JvmDowngradeStepSpec::class.java).apply {
            target.set(JavaVersion.VERSION_17)
            classVersion.set(52)
            api.from(javaApiJar())
        }
        val ctx = ctx()
        val out = JvmDowngradeStepType().create(stepSpec, ctx)
            .apply(inputJar(Opcodes.V17), ctx)
        assertEquals(52, classVersion(out))
    }

    @Test
    fun `shade inlines the used api stub under the prefix`() {
        val stepSpec = objects.newInstance(JvmDowngradeStepSpec::class.java).apply {
            target.set(JavaVersion.VERSION_1_8)
            api.from(javaApiJar())
        }
        val ctx = ctx()
        val out = JvmDowngradeStepType().create(stepSpec, ctx)
            .apply(inputJar(Opcodes.V17, withModernApiCall = true), ctx)

        assertEquals(52, classVersion(out))
        java.util.jar.JarFile(out.toFile()).use { jar ->
            // the isBlank call is rewritten to a j11 stub — shading
            // inlines it under the <basename>/ prefix (the stub's own
            // v55 bytecode lands under META-INF/versions/10 — mr-jar
            // layout keeps the jar valid on a v52 reader)
            val names = jar.entries().toList().map { it.name }
            val stub = names.firstOrNull {
                it.endsWith("jvmdg/j11/stub/java_base/J_L_String.class") &&
                        it.contains("in-v61/")
            }
            assertNotNull(stub,
                "expected a shaded j11 stub for isBlank under in-v61/, entries: " +
                        names.joinToString())
        }
    }

    @Test
    fun `missing target fails clearly`() {
        val stepSpec = objects.newInstance(JvmDowngradeStepSpec::class.java)
        val ctx = ctx()
        val e = assertThrows(GradleException::class.java) {
            JvmDowngradeStepType().create(stepSpec, ctx).apply(inputJar(Opcodes.V17), ctx)
        }
        assertTrue(e.message!!.contains("downgrade"))
    }

}
