package xyz.wagyourtail.unimined.transform.jarmod.internal.modloader

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass

/** `jarmod-modloader-fixes` step — ModLoader dev-environment fixes. */
abstract class ModLoaderDevFixesStepSpec : DevArtifactOnlyStepSpec {
    @get:Input
    abstract val enabled: Property<Boolean>
}

class ModLoaderDevFixesStepType : RemapStepType<ModLoaderDevFixesStepSpec> {
    override val name = "unimined"
    override val stepName = "jarmod-modloader-fixes"
    override val specType: KClass<ModLoaderDevFixesStepSpec> = ModLoaderDevFixesStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)
    override fun supports(spec: RemapSpec) = true
    override fun create(spec: ModLoaderDevFixesStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        if (!spec.enabled.getOrElse(false)) return@ArtifactStep input
        val workDir = Files.createDirectories(c.workDir.resolve("jarmod-modloader-fixes"))
        val output = workDir.resolve("fixed.jar")
        if (!Files.exists(output)) {
            val tmp = workDir.resolve("tmp.jar")
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            FileSystems.newFileSystem(tmp).use { fs -> ModLoaderDevFixes.fix(fs) }
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING)
        }
        output
    }
}
