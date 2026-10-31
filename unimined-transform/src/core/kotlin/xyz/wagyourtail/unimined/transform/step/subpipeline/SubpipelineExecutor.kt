package xyz.wagyourtail.unimined.transform.step.subpipeline

import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Executes a [DeclaredSubpipeline]: seeds bindings + descriptor vars,
 * walks substeps in order interpolating `{x}`-style args (prior step
 * outputs via `{nameOutput}`, the step's own `{output}`/`{log}`),
 * resolves each step's function — registry impl first, then the
 * descriptor's tool table (external javaexec). Step outputs land in
 * `<workDir>/<stepName>/`; the final step's output is the
 * subpipeline's artifact.
 *
 * Generalizes `McpConfigRunner`'s step-graph mechanics so any
 * descriptor format (mcpconfig, NFRT) executes identically.
 */
class SubpipelineExecutor(
    private val pipeline: DeclaredSubpipeline,
    private val format: PipelineDescriptorFormat,
    private val functions: Map<String, PipelineFunction>,
    private val bindings: Map<String, () -> File>,
    private val toolResolver: (String) -> List<Path>,
    private val listBindings: Map<String, () -> List<File>> = emptyMap(),
    /** Injected tool-exec port — when set, receives the resolved
     *  classpath and wins over the builtin [ProcessExec] fallback
     *  (lets a Gradle-aware caller keep toolchain exec). */
    private val toolExec: ((DeclaredTool, List<String>, List<String>, List<Path>) -> Unit)? = null,
) {

    private val outputs = mutableMapOf<String, Path>()
    private var descriptor: File? = null
    private var workDir: Path? = null

    /** Execute steps in order into [workDir]; returns the last
     *  step's output, or stops early at [untilStep] returning its
     *  output (lazy `resultFor`-style semantics — steps after it
     *  are not run). */
    fun execute(
        descriptor: File,
        side: String?,
        stepContext: StepContext?,
        workDir: Path,
        untilStep: String? = null,
    ): Path? {
        this.descriptor = descriptor
        this.workDir = workDir
        var last: Path? = null
        for (step in pipeline.steps) {
            val stepDir = workDir.resolve(step.name).createDirectories()
            val output = stepDir.resolve("${step.name}Output.jar")
            // 1.x FunctionStep parity: an existing output means the
            // step already ran — re-use it (keeps warm-cache evals,
            // incl. CC-store serialization, process-free). Steps
            // whose functions return non-canonical paths (bindings,
            // input copies) never hit this and re-run cheaply.
            if (output.exists()) {
                outputs[step.name] = output
                last = output
                if (untilStep == step.name) return last
                continue
            }
            val stepVars = step.args.mapValues { (_, v) ->
                interpolate(v, output)
            }
            val vars = pipeline.vars + stepVars
            val ctx = object : SubpipelineContext {
                override val workDir = stepDir
                override val descriptor = descriptor
                override val vars = vars
                override val outputs = this@SubpipelineExecutor.outputs
                override val stepContext = stepContext
                override fun binding(name: String) = bindings[name]?.invoke()
                override fun listBinding(name: String) =
                    listBindings[name]?.invoke() ?: emptyList()
                override fun execTool(
                    tool: DeclaredTool,
                    args: List<String>, jvmArgs: List<String>
                ) {
                    val cp = toolResolver(tool.coord)
                    // ports expect a resolved main class (1.x
                    // manifest-scan parity — DeclaredTool leaves it
                    // null when the descriptor omits one)
                    // manifest scan prefers jars matching the coord's
                    // artifact name — callers may pass a union
                    // classpath of all tools
                    val artifact = tool.coord
                        .substringAfter(':').substringBefore(':')
                    val scanOrder = cp.sortedByDescending {
                        it.fileName.toString().contains(artifact)
                    }
                    val resolved = if (tool.mainClass != null) tool
                    else tool.copy(
                        mainClass = scanOrder.firstNotNullOfOrNull { jar ->
                            runCatching {
                                JarFile(jar.toFile()).use {
                                    it.manifest.mainAttributes.getValue(
                                        java.util.jar.Attributes.Name.MAIN_CLASS
                                    )
                                }
                            }.getOrNull()
                        }
                    )
                    if (toolExec != null)
                        toolExec(resolved, args, jvmArgs, cp)
                    else ProcessExec.exec(
                        cp, resolved.mainClass, args, jvmArgs,
                        resolved.javaVersion
                    )
                }
            }
            val fn = functions[step.function]
            last = if (fn != null) {
                fn.run(step, ctx)
            } else {
                val tool = pipeline.functions[step.function]
                    ?: error(
                        "No pipeline function or tool for " +
                                "'${step.function}' (step '${step.name}')"
                    )
                ctx.execTool(
                    tool,
                    tool.args.map { interpolate(it, output, stepVars, true) },
                    tool.jvmArgs.map { interpolate(it, output, stepVars, true) }
                )
                if (output.exists()) output else null
            }
            if (last != null) outputs[step.name] = last!!
            if (untilStep == step.name) return last
        }
        return last
    }

    /** `{nameOutput}` → prior output; `{output}`/`{log}` → this
     *  step's; else [lookup] (merged vars when interpolating tool
     *  args — injected step vars like `patch`'s `prefix` resolve
     *  there), bindings, or descriptor-level vars. */
    private fun interpolate(
        value: String,
        output: Path,
        lookup: Map<String, String> = pipeline.vars,
        extractFallback: Boolean = false,
    ): String =
        value.replace(Regex("\\{([^}]+)}")) {
            val n = it.groupValues[1]
            when {
                n == "output" -> output.absolutePathString()
                n == "log" ->
                    output.resolveSibling("log.txt").absolutePathString()
                n.endsWith("Output") ->
                    outputs[n.removeSuffix("Output")]
                        ?.absolutePathString()
                        ?: error("No output from step '${n.removeSuffix("Output")}'")
                else -> lookup[n]
                    ?: bindings[n]?.invoke()?.absolutePath
                    ?: extractFallback.takeIf { it }?.let {
                        format.extractData(
                            descriptor!!, n, pipeline.vars, workDir!!
                        )?.absolutePathString()
                    }
                    ?: pipeline.vars[n]
                    ?: error("Unknown variable: $n")
            }
        }

    /** Minimal `java`-binary exec — the artifact-transform context
     *  has no Gradle `ExecOperations`; tools run on the build JVM
     *  unless [toolResolver] front-loads a toolchain-specific binary
     *  (a Gradle-backed port can override later). */
    internal object ProcessExec {
        fun exec(
            classpath: List<Path>, mainClass: String?,
            args: List<String>, jvmArgs: List<String>,
            javaVersion: Int?
        ) {
            val main = mainClass ?: classpath.firstNotNullOfOrNull { jar ->
                runCatching {
                    JarFile(jar.toFile()).use {
                        it.manifest.mainAttributes.getValue(
                            java.util.jar.Attributes.Name.MAIN_CLASS
                        )
                    }
                }.getOrNull()
            } ?: error("no main class resolvable for tool")
            val java = File(System.getProperty("java.home"), "bin/java")
            ProcessBuilder(
                listOf(java.absolutePath) + jvmArgs +
                        listOf("-cp", classpath.joinToString(File.pathSeparator), main) +
                        args
            ).inheritIO().start().waitFor().also {
                check(it == 0) { "tool exec failed ($it): $main" }
            }
        }
    }
}
