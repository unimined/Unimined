package xyz.wagyourtail.unimined.remap.transform

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.BundleMappingData
import xyz.wagyourtail.unimined.remap.internal.PipelineRunner
import xyz.wagyourtail.unimined.remap.internal.RuntimeLocation
import xyz.wagyourtail.unimined.remap.internal.RuntimeRemapSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import java.nio.file.Files

/**
 * Per-dependency remap: converts a `jar` artifact into one carrying
 * `unimined.namespace=<target>` by running the declared step pipeline.
 *
 * Runs inside Gradle's transform execution — cacheable, parallel, and
 * fired by any artifact resolution including IDE sync. Step impls are
 * rediscovered via `ServiceLoader` on the transform's classloader; mapping
 * data arrives through the serialized [MappingBundle] interchange.
 */
@CacheableTransform
abstract class RemapDependencyTransform : TransformAction<RemapDependencyTransform.Params> {

    interface Params : TransformParameters {

        @get:Input
        val sourceNamespace: Property<String>

        @get:Input
        val targetNamespace: Property<String>

        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val mappingFile: RegularFileProperty

        /** Serialized [MappingBundle] content — preferred over
         *  [mappingFile] since a string input carries no task dependency
         *  (transforms execute outside the task graph). */
        @get:Optional
        @get:Input
        val mappingBundleJson: Property<String>

        @get:InputFiles
        @get:Classpath
        val classpath: ConfigurableFileCollection

        @get:Nested
        val stepSpecs: ListProperty<StepSpec>

    }

    @get:InputArtifact
    @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile.toPath()
        val spec = RuntimeRemapSpec(
            parameters.sourceNamespace.get(),
            parameters.targetNamespace.get(),
            parameters.classpath.files.map(::RuntimeLocation)
        )
        val mappings = parameters.mappingBundleJson.orNull
            ?.let { BundleMappingData(MappingBundle.fromJson(it)) }
            ?: parameters.mappingFile.orNull
                ?.let { BundleMappingData(MappingBundle.read(it.asFile.toPath())) }

        val output = outputs.file(
            "${input.toFile().nameWithoutExtension}-${spec.targetNamespace}.jar"
        ).toPath()
        val workDir = Files.createTempDirectory("unimined-remap-")
        try {
            PipelineRunner.run(
                input = input,
                output = output,
                surface = RemapSurface.JAR,
                stepSpecs = parameters.stepSpecs.get(),
                spec = spec,
                mappings = mappings,
                classpath = parameters.classpath.files.map { it.toPath() },
                workDir = workDir,
                logger = Logging.getLogger(RemapDependencyTransform::class.java),
                host = "remap transform ${input.fileName}"
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

}
