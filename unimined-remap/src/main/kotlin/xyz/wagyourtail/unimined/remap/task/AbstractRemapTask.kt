package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.remap.internal.PipelineRunner
import xyz.wagyourtail.unimined.remap.internal.RuntimeLocation
import xyz.wagyourtail.unimined.remap.internal.RuntimeRemapSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Runs one [RemapSurface]'s declared step pipeline over an artifact.
 *
 * The task captures *specs*, not engine impls: [stepSpecs] carries the
 * declared [StepSpec]s (serializable into the configuration cache), and a
 * fresh `RemapStepRegistry` rediscovers the impls via `ServiceLoader` at
 * execution time. The pipeline runs in the task action; engine-classpath
 * isolation (worker action) is a planned optimization, not a requirement.
 */
@CacheableTask
abstract class AbstractRemapTask : DefaultTask() {

    /** The artifact to run the pipeline over. */
    @get:InputFile
    @get:Classpath
    abstract val inputArtifact: RegularFileProperty

    /** Where the final artifact is written. */
    @get:OutputFile
    abstract val outputArtifact: RegularFileProperty

    /** The surface whose pipeline this task runs — fixed per subclass. */
    @get:Input
    abstract val surface: Property<RemapSurface>

    /** The declared pipeline steps — wire via `remap.stepSpecs(surface)`. */
    @get:Nested
    abstract val stepSpecs: ListProperty<StepSpec>

    @get:Input
    abstract val sourceNamespace: Property<String>

    @get:Input
    abstract val targetNamespace: Property<String>

    /** Classpath entries for steps that declare
     *  [xyz.wagyourtail.unimined.remap.spi.RemapStepType.needsClasspath]. */
    @get:InputFiles
    @get:Classpath
    abstract val remapClasspath: ConfigurableFileCollection

    /**
     * Serialized mapping bundle input — the configuration-cache-safe way
     * to feed resolved mappings to `needsMappingTree` steps. Accepts either
     * a [SerializedMappingData] zip or a legacy row [MappingBundle].
     * Mutually exclusive with [mappingData]; the file wins when both are set.
     */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /**
     * Resolved mapping data as a live object — programmatic alternative to
     * [mappingFile] for callers that already hold one (e.g. in-build
     * orchestration). `@Internal`: arbitrary `MappingData` impls are not
     * configuration-cache serializable, so prefer [mappingFile].
     */
    @get:Internal
    abstract val mappingData: Property<MappingData>

    @TaskAction
    fun runPipeline() {
        val mappings = mappingFile.orNull
            ?.let { SerializedMappingData.readEither(it.asFile.toPath()) }
            ?: mappingData.orNull

        PipelineRunner.run(
            input = inputArtifact.get().asFile.toPath(),
            output = outputArtifact.get().asFile.toPath(),
            surface = surface.get(),
            stepSpecs = stepSpecs.get(),
            spec = RuntimeRemapSpec(
                sourceNamespace.get(),
                targetNamespace.get(),
                remapClasspath.files.map(::RuntimeLocation)
            ),
            mappings = mappings,
            classpath = remapClasspath.files.map { it.toPath() },
            workDir = temporaryDir.toPath(),
            logger = logger,
            host = path
        )
    }

}
