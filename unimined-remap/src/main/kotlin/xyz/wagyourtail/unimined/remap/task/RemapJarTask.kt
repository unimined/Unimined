package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts
import xyz.wagyourtail.unimined.remap.internal.PipelineRunner
import xyz.wagyourtail.unimined.remap.internal.RuntimeRemapSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Builds the production artifact directly: the task *is* a [Jar] whose
 * spec mirrors the development jar's (`rootSpec.with(jar.rootSpec)` +
 * `manifest.from(jar.manifest)` — wired by the registration site), so it
 * depends on the jar's **inputs** (classes, resources) rather than on
 * the `jar` task's output the way 1.x `remapJar` did.
 *
 * [copy] assembles the mirrored contents to a scratch jar, then runs
 * the declared [RemapSurface.JAR] step pipeline dev→prod over it into
 * [archiveFile].
 */
@CacheableTask
abstract class RemapJarTask : Jar(), ProducesArtifacts {

    /** The surface whose pipeline this task runs — JAR for binaries,
     *  SOURCES for `remapSourcesJar`. */
    @get:Input
    abstract val surface: Property<RemapSurface>

    /** The declared pipeline steps — wire via `remap.pipelineSteps(env, JAR)`. */
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
     * [mappingFile]. `@Internal`: arbitrary `MappingData` impls are not
     * configuration-cache serializable, so prefer [mappingFile].
     */
    @get:Internal
    abstract val mappingData: Property<MappingData>

    /**
     * Serialized [AccessModel] — the merged AW/AT/CT rules of the remap
     * batch, fed to `needsAccessModel` steps as the analysis overlay.
     * Text input keeps the fingerprint content-addressed without a task
     * dependency; absent means no overlay.
     */
    @get:Optional
    @get:Input
    abstract val accessModel: Property<String>

    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("jar" to archiveFile)

    @TaskAction
    public override fun copy() {
        val jarFile = archiveFile.get()
        // assemble the mirrored spec straight into the archive, then
        // remap it in place — steps already support input == output
        // (the dev pipeline does the same for the MC artifact)
        super.copy()

        val mappings = mappingFile.orNull
            ?.let { SerializedMappingData.readEither(it.asFile.toPath()) }
            ?: mappingData.orNull

        PipelineRunner.run(
            input = jarFile.asFile.toPath(),
            output = jarFile.asFile.toPath(),
            surface = surface.get(),
            stepSpecs = stepSpecs.get(),
            spec = RuntimeRemapSpec(
                sourceNamespace.get(),
                targetNamespace.get(),
                remapClasspath.files.map({ it.toPath() })
            ),
            mappings = mappings,
            classpath = remapClasspath.files.map { it.toPath() },
            workDir = temporaryDir.toPath(),
            logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(logger),
            host = path,
            accessModel = accessModel.orNull?.let(AccessModel::parse)
        )
    }

}
