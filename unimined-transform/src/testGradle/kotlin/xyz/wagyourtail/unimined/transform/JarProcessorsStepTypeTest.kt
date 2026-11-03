package xyz.wagyourtail.unimined.transform

import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.commons.core.logger.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.processor.TestJarProcessor
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * The `jar-processors` step resolves each `name payload` entry through
 * the ServiceLoader'd registry and invokes `processJar` in order.
 */
class JarProcessorsStepTypeTest {

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun reset() = TestJarProcessor.reset()

    private val ctx
        get() = object : StepContext {
     override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "named"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings: MappingData? = null
            override val classpath = emptyList<Path>()
            override val workDir = dir
            override val logger: Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(JarProcessorsStepTypeTest::class.java))
        }

    private fun inputJar(): Path {
        val jar = dir.resolve("input.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("a/A.class"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
        }
        return jar
    }

    private fun spec(vararg payloads: String): JarProcessorsStepSpec {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(JarProcessorsStepSpec::class.java).apply {
            processorSpecs.set(payloads.toList())
        }
    }

    @Test
    fun `empty spec set returns the input unchanged`() {
        val input = inputJar()
        val out = JarProcessorsStepType().create(spec(), ctx).apply(input, ctx)
        assertSame(input, out)
    }

    @Test
    fun `declared processor runs over a copy of the artifact`() {
        val input = inputJar()
        val out = JarProcessorsStepType()
            .create(spec("test-processor my-payload"), ctx)
            .apply(input, ctx)

        // the processor saw the deserialized payload over the copy
        assertEquals(listOf(out to "my-payload"), TestJarProcessor.calls)

        JarFile(out.toFile()).use { jar ->
            assertNotNull(jar.getEntry("a/A.class"))
            assertNotNull(jar.getEntry(TestJarProcessor.MARKER))
        }
        // the input is untouched — steps copy, never mutate in place
        JarFile(input.toFile()).use { jar ->
            assertNull(jar.getEntry(TestJarProcessor.MARKER))
        }
    }

    @Test
    fun `unknown processor name fails the step`() {
        val input = inputJar()
        val ex = org.junit.jupiter.api.assertThrows<org.gradle.api.GradleException> {
            JarProcessorsStepType().create(spec("missing-processor x"), ctx).apply(input, ctx)
        }
        assertEquals(true, ex.message!!.contains("missing-processor"))
    }

}
