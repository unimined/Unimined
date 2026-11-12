package xyz.wagyourtail.unimined.remap.internal

import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.Perf
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StepPipeline
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.commonskt.jvm.moveAtomicTo
import xyz.wagyourtail.commonskt.jvm.tempSibling
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/**
 * Shared step-execution core for hosts that run a declared pipeline over an
 * artifact on disk — remap tasks and artifact transforms alike. Resolves
 * each declared [StepSpec] through a `ServiceLoader`-discovered
 * [RemapStepRegistry], materializes [TaskStepContext] from the resolved
 * impls' `needs*` flags, runs [StepPipeline], and copies the result to
 * [output].
 */
object PipelineRunner {

    fun run(
        input: Path,
        output: Path,
        surface: RemapSurface,
        stepSpecs: List<StepSpec>,
        spec: RemapSpec,
        mappings: MappingData?,
        classpath: List<Path>,
        workDir: Path,
        logger: Logger,
        host: String,
        accessModel: AccessModel? = null,
        envSide: xyz.wagyourtail.unimined.api.spec.Side? = null
    ) {
        val registry = RemapStepRegistry()
        val resolved = stepSpecs.map { registry.resolve(it, surface, spec) }
        // host is "<envLabel>:<verb> <artifact>" (artifact transforms) or a
        // Gradle task path (tasks) — envLabel scopes the logger
        // (`[Unimined/<env>`), the rest stays in the message so concurrent
        // transforms on different artifacts stay identifiable
        val envLabel = host.removePrefix(":").substringBefore(':')
        val detail = host.substringAfter(':', "").trim()
        val prefix = if (detail.isEmpty()) "" else "$detail — "
        val base = logger
        val log = uniminedLogger(envLabel, base)

        val ctx = TaskStepContext(
            surface = surface,
            spec = spec,
            mappings = if (resolved.any { it.needsMappingTree }) {
                mappings ?: throw IllegalStateException(
                    "$host: pipeline requires mapping data — provide a mapping bundle"
                )
            } else null,
            classpath = if (resolved.any { it.needsClasspath }) classpath else emptyList(),
            workDir = workDir,
            logger = base,
            // the analysis overlay is additive — absent is a valid state
            // (no access rules declared anywhere in the batch)
            accessModel = if (resolved.any { it.needsAccessModel }) accessModel else null,
            log = log,
            state = PipelineState(spec.sourceNamespace, envSide)
        )

        val steps = stepSpecs.zip(resolved)
            .map { (stepSpec, type) ->
                val delegate = type.create(stepSpec, ctx)
                if (delegate is FusibleJarStep) {
                    // fusible steps need their interface to survive the
                    // timing wrapper — accumulate prep+visit time and
                    // emit on endFused, when the group has finished
                    object : FusibleJarStep {
                        private var ns = 0L
                        override fun prepareFused(input: Path, c: StepContext) {
                            log.lifecycle(
                                "${prefix}preparing step ${type.name}:${type.stepName} on ${input.fileName}"
                            )
                            val t0 = System.nanoTime()
                            delegate.prepareFused(input, c)
                            ns = System.nanoTime() - t0
                        }
                        override fun visitEntry(entry: FusedJarEntry, c: StepContext): FusedJarEntry? {
                            val t0 = System.nanoTime()
                            val r = delegate.visitEntry(entry, c)
                            ns += System.nanoTime() - t0
                            return r
                        }
                        override fun visitDirectory(entry: FusedJarEntry, c: StepContext): Boolean =
                            delegate.visitDirectory(entry, c)
                        override fun endFused(c: StepContext) {
                            delegate.endFused(c)
                            val stepMs = ns / 1_000_000
                            log.lifecycle(
                                "${prefix}step ${type.name}:${type.stepName} " +
                                        "(fused) in ${stepMs}ms"
                            )
                            Perf.emit("step", "host" to envLabel,
                                "step" to "${type.name}:${type.stepName}",
                                "fused" to true, "ms" to stepMs)
                        }
                    }
                } else {
                    // timed wrapper — start/finish lines identify which
                    // env/artifact each step is running on
                    ArtifactStep { input, c ->
                        log.lifecycle("${prefix}running step ${type.name}:${type.stepName} on ${input.fileName}")
                        val t0 = System.nanoTime()
                        val out = delegate.apply(input, c)
                        val stepMs = (System.nanoTime() - t0) / 1_000_000
                        log.lifecycle(
                            "${prefix}step ${type.name}:${type.stepName} " +
                                    "${input.fileName} -> ${out.fileName} in " +
                                    "${stepMs}ms"
                        )
                        Perf.emit("step", "host" to envLabel,
                            "step" to "${type.name}:${type.stepName}",
                            "input" to input.fileName.toString(), "ms" to stepMs)
                        out
                    }
                }
            }

        log.info("${prefix}pipeline on ${input.fileName} (${steps.size} steps)")
        val t0 = System.nanoTime()
        val out = StepPipeline.run(input, steps, ctx)
        val pipelineMs = (System.nanoTime() - t0) / 1_000_000
        log.lifecycle(
            "${prefix}pipeline ${input.fileName} -> ${out.fileName} done in " +
                    "${pipelineMs}ms"
        )
        Perf.emit("pipeline", "host" to envLabel,
            "input" to input.fileName.toString(),
            "steps" to steps.size, "ms" to pipelineMs)
        if (out != output) {
            output.parent?.createDirectories()
            // temp + atomic move — a killed pipeline must not leave a
            // partial jar at the artifact path (#78)
            val tmp = output.tempSibling()
            try {
                Files.copy(out, tmp, StandardCopyOption.REPLACE_EXISTING)
                tmp.moveAtomicTo(output)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

}
