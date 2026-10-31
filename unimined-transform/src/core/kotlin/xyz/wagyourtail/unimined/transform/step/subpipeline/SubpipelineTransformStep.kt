package xyz.wagyourtail.unimined.transform.step.subpipeline

import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.step.TransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.File
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Executes a runtime-defined subpipeline as a single
 * [TransformStep] — the descriptor ([descriptor], e.g. a resolved
 * mcpconfig/NFRT zip) is parsed at execution time by the
 * [PipelineDescriptorFormat] named [format], seeded with [bindings],
 * and run by [SubpipelineExecutor]. The final substep's output
 * becomes this step's artifact.
 *
 * Serializable per the [TransformStep] contract — resolution of the
 * format + function registries happens inside [transform].
 */
class SubpipelineTransformStep(
    val format: String,
    val descriptor: () -> File,
    val bindings: Map<String, () -> File> = emptyMap(),
    val listBindings: Map<String, () -> List<File>> = emptyMap(),
    val toolResolver: (String) -> List<Path> = { emptyList() },
    val side: String? = null,
) : TransformStep {

    override fun transform(input: Path, ctx: TransformStepContext): Path {
        val formatImpl = SubpipelineRegistry.format(format)
            ?: error(
                "No PipelineDescriptorFormat named '$format' — " +
                        "the transform module providing it must be " +
                        "on the plugin classpath"
            )
        val pipeline = formatImpl.parse(descriptor(), object : FormatContext {
            override val side = this@SubpipelineTransformStep.side
            override val bindingNames = bindings.keys
        })
        val output = SubpipelineExecutor(
            pipeline, formatImpl, SubpipelineRegistry.functions(),
            bindings, toolResolver, listBindings
        ).execute(descriptor(), side, ctx.stepContext, ctx.workDir)
        return output ?: input
    }
}

/** ServiceLoader resolution for descriptor formats + functions. */
internal object SubpipelineRegistry {

    fun formats(): List<PipelineDescriptorFormat> =
        ServiceLoader.load(
            PipelineFormatSource::class.java,
            PipelineFormatSource::class.java.classLoader
        ).flatMap { it.formats() }

    fun functions(): Map<String, PipelineFunction> =
        ServiceLoader.load(
            PipelineFunctionSource::class.java,
            PipelineFunctionSource::class.java.classLoader
        ).flatMap { it.functions() }.associateBy { it.name }

    fun format(name: String): PipelineDescriptorFormat? =
        formats().firstOrNull { it.name == name }
}
