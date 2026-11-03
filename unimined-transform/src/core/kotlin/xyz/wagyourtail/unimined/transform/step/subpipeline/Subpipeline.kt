package xyz.wagyourtail.unimined.transform.step.subpipeline

import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import java.io.Serializable
import java.nio.file.Path

/**
 * Runtime-defined subpipelines — a pipeline described by data inside
 * a resolved artifact (mcpconfig `config.json`, NeoForm/NFRT
 * descriptors) rather than declared at configuration time. The outer
 * `unimined:subpipeline` step is declared normally; the inner step
 * graph materializes at execution, when the descriptor is on disk.
 *
 * Two ServiceLoader extension points:
 * - [PipelineDescriptorFormat] — parses a descriptor into a
 *   [DeclaredSubpipeline]
 * - [PipelineFunction] — implements one named substep
 *
 * Functions not in the registry fall back to external tool exec via
 * the descriptor's tool table ([DeclaredSubpipeline.functions]).
 */

/** One parsed substep — a named function call with templated args. */
data class SubStep(
    /** Step name — `{nameOutput}` interpolates to its output. */
    val name: String,
    /** Function name — `rename`, `merge`, a `functions.<name>`
     *  entry, or a registry-provided impl. */
    val function: String,
    /** Templated args — `{x}` refs resolve against vars, bindings,
     *  and prior step outputs at execution. */
    val args: Map<String, String> = emptyMap(),
) : Serializable

/** A resolved external tool — one `functions.<name>` entry. */
data class DeclaredTool(
    /** Maven-ish coordinate resolved via the exec-context
     *  `toolResolver`. */
    val coord: String,
    val mainClass: String? = null,
    val args: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
    val javaVersion: Int? = null,
) : Serializable

/** A descriptor parsed into executable form. */
data class DeclaredSubpipeline(
    /** Ordered substeps — executed in list order; later steps may
     *  reference `{priorOutput}` paths. */
    val steps: List<SubStep>,
    /** Descriptor-level constants (`data` keys) usable in arg
     *  templating and by functions (`extractData` semantics live in
     *  format-specific function impls). */
    val vars: Map<String, String> = emptyMap(),
    /** Named external tools the steps may invoke. */
    val functions: Map<String, DeclaredTool> = emptyMap(),
) : Serializable

/** Parse-time context for [PipelineDescriptorFormat.parse]. */
interface FormatContext {
    /** `client`/`server` when the descriptor is side-split. */
    val side: String?
    /** The outer step's declared bindings — available to the format
     *  for interpolation metadata, not yet resolved to files. */
    val bindingNames: Set<String>
}

/** ServiceLoader-discovered descriptor parser. */
interface PipelineDescriptorFormat {
    /** Registry name — `"mcpconfig"`, `"neofrt"`. */
    val name: String
    fun parse(descriptor: File, ctx: FormatContext): DeclaredSubpipeline
    /** Materialize a descriptor `data` key to a file — mcpconfig
     *  extracts the zip entries under `data[key]`'s prefix;
     *  formats without embedded data return null. Called when a
     *  `{var}` in tool args resolves through no other channel
     *  (1.x `resolveVariable` else-branch parity). */
    fun extractData(
        descriptor: File, key: String,
        vars: Map<String, String>, workDir: Path
    ): Path? = null
}

/** Execution context handed to a [PipelineFunction]. */
interface SubpipelineContext {
    /** This substep's scratch directory. */
    val workDir: Path
    /** The descriptor file the subpipeline was parsed from. */
    val descriptor: File
    /** Descriptor-level vars + step args merged, interpolated. */
    val vars: Map<String, String>
    /** Resolved outputs of prior steps, by name. */
    val outputs: Map<String, Path>
    /** Environment bindings, resolved lazily by functions that
     *  consume them (`client`, `mappings`, `libraries`…). */
    fun binding(name: String): File?
    /** Multi-file bindings (`libraries` — env classpath rows). */
    fun listBinding(name: String): List<File> = emptyList()
    /** The remap pipeline context — classpath, mappings, logger.
     *  Null when the subpipeline runs outside a remap pipeline
     *  (e.g. inside a spec-materializing provider). */
    val stepContext: StepContext?
    /** Resolve + run an external tool — formats provide one for
     *  `functions.<name>` fallback entries. */
    fun execTool(tool: DeclaredTool, args: List<String>, jvmArgs: List<String>)
}

/** ServiceLoader-discovered named substep implementation. */
interface PipelineFunction {
    /** Registry name — a [SubStep.function] value. */
    val name: String
    /** Run [step]; return the produced output artifact (null for
     *  no-output steps like downloads/no-ops). */
    fun run(step: SubStep, ctx: SubpipelineContext): Path?
}

/** ServiceLoader source for descriptor formats. */
interface PipelineFormatSource {
    fun formats(): List<PipelineDescriptorFormat>
}

/** ServiceLoader source for pipeline functions. */
interface PipelineFunctionSource {
    fun functions(): List<PipelineFunction>
}
