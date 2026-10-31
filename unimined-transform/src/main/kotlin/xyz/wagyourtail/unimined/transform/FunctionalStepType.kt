package xyz.wagyourtail.unimined.transform

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.transform.step.TransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.File
import kotlin.reflect.KClass

/**
 * Spec for [FunctionalStepType] — a declared pipeline step's
 * functional jar-in/jar-out contribution. Built programmatically by
 * the host wiring when bridging [xyz.wagyourtail.unimined.transform.step.PipelineStepSpec.transformSteps]
 * contributions onto `contributeStep` — never via the `step()` DSL,
 * so it needs no `objects.newInstance` shape.
 *
 * [stepId] is the declaring step's `StepType.id` — it fingerprints
 * the spec for up-to-date checks (the [step] object itself is
 * serialization-only state).
 */
class FunctionalStepSpec(
    val stepId: String,
    val step: TransformStep,
    /** Named aux inputs resolved at wiring from env-artifact
     *  bindings — handed to [TransformStepContext.inputs]. */
    val inputs: Map<String, List<File>> = emptyMap(),
) : StepSpec

/**
 * `unimined:functional` — executes a [TransformStep] contributed by a
 * declared pipeline step inside the artifact pipeline. The generic
 * escape hatch for work with no dedicated [RemapStepType]: merges,
 * launchwrapper weaving, runtime remap-table generation.
 *
 * `needs*` flags are type-level, so this wrapper cannot honor
 * per-step declarations — it conservatively materializes mappings,
 * classpath, and the access model whenever a functional step runs.
 * Functional steps are rare and typically ride pipelines that
 * materialize these anyway.
 */
class FunctionalStepType : RemapStepType<FunctionalStepSpec> {

    override val name = "unimined"
    override val stepName = "functional"
    override val specType: KClass<FunctionalStepSpec> = FunctionalStepSpec::class
    override val surfaces: Set<RemapSurface> = RemapSurface.values().toSet()

    override fun supports(spec: RemapSpec) = true

    override val needsMappingTree get() = true
    override val needsClasspath get() = true
    override val needsAccessModel get() = true

    override fun create(spec: FunctionalStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        spec.step.transform(
            input,
            object : TransformStepContext {
                override val workDir = c.workDir
                override val stepContext = c
                override val inputs: Map<String, List<File>> = spec.inputs
            }
        )
    }

}
