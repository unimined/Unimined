package xyz.wagyourtail.unimined.remap.transform

import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.BundleMappingData
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.remap.internal.ModMarkerScan
import xyz.wagyourtail.unimined.remap.internal.PipelineRunner
import xyz.wagyourtail.unimined.remap.internal.RuntimeRemapSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

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

        /**
         * Identity tag for logs — env + view context
         * (`envId`/`dev`, `envId`/`modImplementation`, …) so
         * parallel transforms from multi-sourceset projects are
         * distinguishable. Optional: absent labels fall back to the
         * input artifact name.
         */
        @get:Optional
        @get:Input
        val label: Property<String>

        /**
         * Mod indicators — when non-empty, artifacts without a marker
         * ([xyz.wagyourtail.unimined.remap.internal.ModMarkerScan])
         * pass through unmapped. Set only on mod views; environment
         * dev-artifact transforms leave it empty so the Minecraft jar
         * (unmarked by definition) is never skipped.
         */
        @get:Optional
        @get:Input
        val modMarkers: ListProperty<String>

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

        /**
         * Serialized [AccessModel] — the merged AW/AT/CT rules of the
         * whole remap batch, identical across invocations, fed to
         * `needsAccessModel` steps as the analysis overlay. A string
         * input carries no task dependency, like [mappingBundleJson].
         */
        @get:Optional
        @get:Input
        val accessModel: Property<String>

        /**
         * The environment's side (`CLIENT`/`SERVER`/`MERGED`) for
         * env-scoped transforms — carried into [PipelineState] so steps
         * and jar processors can validate side-sensitive inputs.
         * Absent on env-agnostic transforms (mod views, standalone use).
         */
        @get:Optional
        @get:Input
        val envSide: Property<String>

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
            parameters.classpath.files.map { it.toPath() }
        )
        val mappings = parameters.mappingBundleJson.orNull
            ?.let { json -> dataCache.computeIfAbsent(json, ::decodeMappingData) }
            ?: parameters.mappingFile.orNull
                ?.let { SerializedMappingData.readEither(it.asFile.toPath()) }

        val output = outputs.file(
            "${input.toFile().nameWithoutExtension}-${spec.targetNamespace}.jar"
        ).toPath()
        // marked-jar filtering (mod views only): jars without a mod
        // indicator pass through unchanged — the pipeline would remap
        // nothing while paying full analysis cost per artifact
        val markers = parameters.modMarkers.getOrElse(emptyList())
        if (markers.isNotEmpty() && !ModMarkerScan.isModJar(input, markers)) {
            uniminedLogger(
                parameters.label.orNull ?: "",
                Logging.getLogger(RemapDependencyTransform::class.java)
            ).info("remap — no mod markers in ${input.fileName}; passing through unmapped")
            Files.copy(input, output)
            return
        }
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
                logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(RemapDependencyTransform::class.java)),
                host = "${parameters.label.orNull?.let { "$it:" } ?: ""}remap ${input.fileName}",
                accessModel = parameters.accessModel.orNull?.let(AccessModel::parse),
                envSide = parameters.envSide.orNull?.let(Side::valueOf)
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private companion object {

        /**
         * Decode the [Params.mappingBundleJson] input — `z64:` marks a
         * base64 [SerializedMappingData] zip (rich views incl. mapping
         * element metadata); anything else is a legacy row-only
         * [MappingBundle] JSON.
         */
        private fun decodeMappingData(json: String): MappingData =
            if (json.startsWith("z64:")) {
                val tmp = Files.createTempFile("unimined-mappings", ".zip")
                try {
                    Files.write(tmp, java.util.Base64.getDecoder().decode(json.substring(4)))
                    SerializedMappingData.readEither(tmp)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            } else {
                BundleMappingData(MappingBundle.fromJson(json))
            }

        /**
         * Daemon-lifetime parse-once cache for decoded mapping data —
         * replaces the row-only [MappingBundle] cache; the z64 payload
         * decodes to the full serialized view set.
         * a remap view fires this transform once per dependency artifact,
         * and every invocation would otherwise re-parse the same
         * serialized tree (hundreds of MB live) concurrently.
         */
        val dataCache = ConcurrentHashMap<String, MappingData>()

    }

}
