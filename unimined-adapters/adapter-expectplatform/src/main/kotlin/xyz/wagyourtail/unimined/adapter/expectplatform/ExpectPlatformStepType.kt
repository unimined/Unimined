package xyz.wagyourtail.unimined.adapter.expectplatform

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.expect.TransformPlatform
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.reflect.KClass

/**
 * `expectPlatform` implementation backed by ExpectPlatform's
 * [TransformPlatform]: statically resolves the annotation machinery
 * into the jar so prod runs and shipped artifacts need no agent.
 * Environment stripping is deliberately **not** done here — prod
 * jars stay side-agnostic; loaders read their own (remapped)
 * annotations and strip at runtime.
 */
class ExpectPlatformStepType : RemapStepType<ExpectPlatformStepSpec> {

    override val name = "expectplatform"
    override val stepName = "expectPlatform"
    override val specType: KClass<ExpectPlatformStepSpec> = ExpectPlatformStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: ExpectPlatformStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val transformer = TransformPlatform(
            spec.platformName.get(),
            spec.remap.get(),
            spec.stripAnnotations.getOrElse(false)
        )
        val output = Files.createTempDirectory(c.workDir, "expectPlatform")
            .resolve(input.fileName.toString())
        FileSystems.newFileSystem(input, null as ClassLoader?).use { inputFs ->
            FileSystems.newFileSystem(output, mapOf("create" to "true")).use { outputFs ->
                transformer.transform(inputFs.getPath("/"), outputFs.getPath("/"))
            }
        }
        output
    }

}
