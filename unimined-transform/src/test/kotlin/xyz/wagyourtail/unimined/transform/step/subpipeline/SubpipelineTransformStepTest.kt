package xyz.wagyourtail.unimined.transform.step.subpipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * [SubpipelineTransformStep] end-to-end through the ServiceLoader
 * registry — a test `PipelineDescriptorFormat`/`PipelineFunction`
 * pair (registered under `src/test/resources/META-INF/services`)
 * proves the `format` lookup, `descriptor`/`bindings`/`side`/
 * `toolResolver` plumbing, and the `output ?: input` fallback.
 * The descriptor syntax is a test-local `key=value` format, so this
 * exercises the registry indirection rather than mcpconfig itself.
 */
class SubpipelineTransformStepTest {

    @TempDir
    lateinit var dir: Path

    private val stepCtx: StepContext by lazy {
        object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState(spec.sourceNamespace, null)
        }
        override val spec = object : RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath: List<Path> = emptyList()
        }
        override val mappings: MappingData? = null
        override val classpath = emptyList<Path>()
        override val workDir = dir.resolve("step-work")
        override val logger: Logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("subpipeline-test"))
        }
    }

    private fun ctx() = object : TransformStepContext {
        override val workDir = dir.resolve("transform-work")
        override val stepContext = stepCtx
    }

    /** `step=name:function` lines — a minimal descriptor. */
    private fun descriptor(vararg lines: String): File {
        val f = dir.resolve("desc.txt").toFile()
        f.writeText(lines.joinToString("\n"))
        return f
    }

    @Test
    fun `transform parses the descriptor and runs the pipeline`() {
        val input = dir.resolve("in.jar").also {
            it.writeBytes("input".toByteArray()) }
        val binding = dir.resolve("bound.jar").toFile()
            .also { it.writeBytes(byteArrayOf(0)) }
        val step = SubpipelineTransformStep(
            format = "testfmt",
            descriptor = { descriptor("step=one:testEmit") },
            bindings = mapOf("bound" to { binding }),
        )
        val out = step.transform(input, ctx())
        // the test function writes the canonical step output
        assertTrue(out.fileName.toString() == "oneOutput.jar")
        assertEquals("emitted", String(Files.readAllBytes(out)))
        // and the binding it consumed was the declared one
        assertEquals(binding.absolutePath,
            TestEmitFunction.lastBinding)
    }

    @Test
    fun `transform returns input when the pipeline produces nothing`() {
        val input = dir.resolve("in.jar").also {
            it.writeBytes("input".toByteArray()) }
        val step = SubpipelineTransformStep(
            format = "testfmt",
            descriptor = { descriptor("step=one:testNoop") },
        )
        assertSame(input, step.transform(input, ctx()))
    }

    @Test
    fun `unknown format is a wiring error naming the format`() {
        val input = dir.resolve("in.jar").also {
            it.writeBytes(byteArrayOf(0)) }
        val step = SubpipelineTransformStep(
            format = "no-such-format",
            descriptor = { descriptor("step=one:testEmit") },
        )
        val e = assertThrows<IllegalStateException> {
            step.transform(input, ctx())
        }
        assertTrue(e.message!!.contains("no-such-format"), e.message)
    }

}

/** Test descriptor format — `step=<name>:<fn>` per line. */
class TestDescriptorFormat : PipelineDescriptorFormat {
    override val name = "testfmt"
    override fun parse(descriptor: File, ctx: FormatContext) =
        DeclaredSubpipeline(
            steps = descriptor.readLines().filter { it.isNotBlank() }
                .map { line ->
                    val (name, fn) = line.removePrefix("step=").split(":")
                    SubStep(name, fn)
                })
}

/** Test `PipelineFunction` — writes a canonical output and records
 *  the `bound` binding it saw (proving the binding plumbing). */
class TestEmitFunction : PipelineFunction {
    override val name = "testEmit"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path {
        lastBinding = ctx.binding("bound")?.absolutePath
        val out = ctx.workDir.resolve("${step.name}Output.jar")
        out.writeBytes("emitted".toByteArray())
        return out
    }

    companion object {
        var lastBinding: String? = null
    }
}

/** No-output function — the transform then returns the input. */
class TestNoopFunction : PipelineFunction {
    override val name = "testNoop"
    override fun run(step: SubStep, ctx: SubpipelineContext): Path? = null
}

/** ServiceLoader source — the test format. */
class TestFormatSource : PipelineFormatSource {
    override fun formats() = listOf(TestDescriptorFormat())
}

/** ServiceLoader source — the test functions. */
class TestFunctionSource : PipelineFunctionSource {
    override fun functions() = listOf(TestEmitFunction(), TestNoopFunction())
}
