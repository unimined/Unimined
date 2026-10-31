package xyz.wagyourtail.unimined.transform.step

import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StepScope
import java.io.File
import java.io.Serializable
import java.nio.file.Path

/**
 * A jar-in/jar-out unit of transform work a declared pipeline step
 * contributes — the declared-pipeline equivalent of a remap
 * `ArtifactStep`, for work that does not fit the remap/JarProcessor
 * shapes (merge, launchwrapper weaving, runtime remap tables).
 *
 * Implementations are classes, never lambdas — specs are serialized
 * into task inputs under the configuration cache, so closure capture
 * is forbidden. Keep impls [Serializable] with serializable fields;
 * heavyweight state (files, tool classpaths) lives on the
 * [TransformStepContext] or resolves lazily at execution.
 */
interface TransformStep : Serializable {

    /**
     * Transform [input] and return the produced artifact path.
     * Implementations may write intermediates to
     * [TransformStepContext.workDir] and must not mutate [input]
     * in place.
     */
    fun transform(input: Path, ctx: TransformStepContext): Path

}

/**
 * Execution context handed to [TransformStep.transform] — the remap
 * pipeline's [StepContext] plus named auxiliary inputs.
 */
interface TransformStepContext {

    /** Scratch directory for step intermediates. */
    val workDir: Path

    /** The remap pipeline context — classpath, mappings, state, log. */
    val stepContext: StepContext

    /**
     * Named auxiliary inputs for multi-input steps (e.g. `mergeInputs`
     * second artifacts), resolved to files at wiring time. Empty for
     * single-artifact steps.
     */
    val inputs: Map<String, List<File>>
        get() = emptyMap()

}

/**
 * A declared step's contribution to the artifact pipeline as a
 * [TransformStep] — bridged by the host wiring into a
 * `FunctionalStepSpec` riding the env's `contributeStep` channel,
 * exactly like [RemapStepContribution] rides `stepSpecs()`.
 */
data class TransformStepContribution(
    /** The surface this step runs on. */
    val surface: RemapSurface,
    /** The work to run. */
    val step: TransformStep,
    /** `contributePreStep` — runs before the declared pipeline, on
     *  the original-namespace input. */
    val pre: Boolean = false,
    val scope: StepScope = StepScope.ALL_ARTIFACTS,
    /**
     * Named inputs this step consumes, bound to
     * `TransformEnvironmentSpec.envArtifactBindings` keys — the
     * wiring resolves them inside the deferred provider (where
     * binding lambdas legally evaluate) into
     * [TransformStepContext.inputs]. E.g. `"server"` →
     * `"server_jar"` for a merge step.
     */
    val inputBindings: Map<String, String> = emptyMap(),
) : Serializable
