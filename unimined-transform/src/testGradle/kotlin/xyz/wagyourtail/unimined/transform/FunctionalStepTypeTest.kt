package xyz.wagyourtail.unimined.transform

import org.gradle.api.logging.Logging
import xyz.wagyourtail.commons.core.logger.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.step.TransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * `FunctionalStepSpec.inputs` (resolved env-artifact bindings)
 * reaching [TransformStepContext.inputs] — the `mergeInputs`
 * multi-input channel.
 */
class FunctionalStepTypeTest {

    @TempDir
    lateinit var dir: Path

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
            override val logger: Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(FunctionalStepTypeTest::class.java))
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

    @Test
    fun `spec inputs reach the step's TransformStepContext`() {
        val aux = dir.resolve("aux.jar").toFile().apply { writeBytes(byteArrayOf(9)) }
        var captured: Map<String, List<File>>? = null
        val step = object : TransformStep {
            override fun transform(input: Path, ctx: TransformStepContext): Path {
                captured = ctx.inputs
                return input
            }
        }
        val spec = FunctionalStepSpec(
            "forge:mergeInputs", step,
            mapOf("server" to listOf(aux))
        )
        val input = inputJar()
        FunctionalStepType().create(spec, ctx).apply(input, ctx)
        assertEquals(listOf(aux), captured?.get("server"))
    }

}
