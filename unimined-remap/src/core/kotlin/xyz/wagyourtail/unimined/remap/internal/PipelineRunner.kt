package xyz.wagyourtail.unimined.remap.internal

import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepPipeline
import xyz.wagyourtail.unimined.remap.spi.StepSpec
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
                // timed wrapper — start/finish lines identify which
                // env/artifact each step is running on
                ArtifactStep { input, c ->
                    log.lifecycle("${prefix}running step ${type.name}:${type.stepName} on ${input.fileName}")
                    val t0 = System.nanoTime()
                    val out = delegate.apply(input, c)
                    log.lifecycle(
                        "${prefix}step ${type.name}:${type.stepName} " +
                                "${input.fileName} -> ${out.fileName} in " +
                                "${(System.nanoTime() - t0) / 1_000_000}ms"
                    )
                    out
                }
            }

        log.info("${prefix}pipeline on ${input.fileName} (${steps.size} steps)")
        val t0 = System.nanoTime()
        val out = StepPipeline.run(input, steps, ctx)
        log.lifecycle(
            "${prefix}pipeline ${input.fileName} -> ${out.fileName} done in " +
                    "${(System.nanoTime() - t0) / 1_000_000}ms"
        )
        if (out != output) {
            output.parent?.createDirectories()
            Files.copy(out, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

}
