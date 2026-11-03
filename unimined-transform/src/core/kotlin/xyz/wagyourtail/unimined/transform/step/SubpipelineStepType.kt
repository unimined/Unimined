package xyz.wagyourtail.unimined.transform.step

import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineTransformStep
import java.io.File
import java.nio.file.Path

/**
 * `unimined:subpipeline` — vocabulary for a runtime-defined
 * subpipeline step (mcpconfig `config.json`, NFRT descriptors):
 * the inner step graph materializes at execution from a resolved
 * descriptor, executed by [SubpipelineTransformStep].
 */
object SubpipelineStepType : StepType {
    override val id = "unimined:subpipeline"
}

/**
 * Spec for [SubpipelineStepType] — the declared-pipeline carrier for
 * a runtime-defined subpipeline:
 *
 * ```kotlin
 * pipeline.step("unimined:subpipeline") { spec: SubpipelineStepSpec ->
 *     spec.format = "mcpconfig"
 *     spec.descriptor = { resolvedUserdevZip }
 *     spec.bindings = mapOf("client" to { clientJar })
 *     spec.side = "client"
 * }
 * ```
 */
abstract class SubpipelineStepSpec : PipelineStepSpec {

    /** [PipelineDescriptorFormat.name] — `"mcpconfig"`, `"neofrt"`. */
    var format: String = ""

    /** Lazily-resolved descriptor artifact (resolved inside deferred
     *  providers / at execution, never during configuration). */
    var descriptor: () -> File = { error("subpipeline descriptor unset") }

    /** Named env inputs the substeps bind (`client`, `mappings`…). */
    var bindings: Map<String, () -> File> = emptyMap()

    /** Multi-file bindings (`libraries` — env classpath). */
    var listBindings: Map<String, () -> List<File>> = emptyMap()

    /** Resolve a tool coordinate to its classpath jars for the
     *  descriptor's `functions` fallback entries. */
    var toolResolver: (String) -> List<Path> = { emptyList() }

    /** `client`/`server` for side-split descriptors. */
    var side: String? = null

    /** The surface this step runs on — jar-in/jar-out by default. */
    var surface: RemapSurface = RemapSurface.JAR

    override fun transformSteps(): List<TransformStepContribution> =
        listOf(
            TransformStepContribution(
                surface,
                SubpipelineTransformStep(
                    format, descriptor, bindings, listBindings,
                    toolResolver, side
                )
            )
        )
}

/** ServiceLoader source — the base transform module's step types. */
class TransformStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(
        SubpipelineStepType,
    )

}
