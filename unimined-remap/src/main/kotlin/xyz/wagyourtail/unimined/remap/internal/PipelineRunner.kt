package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
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
        host: String
    ) {
        val registry = RemapStepRegistry()
        val resolved = stepSpecs.map { registry.resolve(it, surface, spec) }

        val ctx = TaskStepContext(
            spec = spec,
            mappings = if (resolved.any { it.needsMappingTree }) {
                mappings ?: throw GradleException(
                    "$host: pipeline requires mapping data — provide a mapping bundle"
                )
            } else null,
            classpath = if (resolved.any { it.needsClasspath }) classpath else emptyList(),
            workDir = workDir,
            logger = logger
        )

        val steps = stepSpecs.zip(resolved)
            .map { (stepSpec, type) -> type.create(stepSpec, ctx) }

        val out = StepPipeline.run(input, steps, ctx)
        if (out != output) {
            output.parent?.createDirectories()
            Files.copy(out, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

}
