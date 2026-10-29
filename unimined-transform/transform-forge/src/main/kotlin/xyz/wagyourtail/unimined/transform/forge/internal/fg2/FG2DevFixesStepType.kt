package xyz.wagyourtail.unimined.transform.forge.internal.fg2

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixes

/**
 * `forge-fg2-fixes` JAR-surface step — the FG2 dev-environment
 * injections ([FG2DevFixes]): classpath coremod discovery, MOD_CLASSES
 * resource/lang loading, runtime-deobf strip, plus splicing the
 * `UniminedFg2` helper class into the jar.
 *
 * Contributed only for `Binpatches`-profile environments;
 * [spec][BinpatchesPackStepSpec]-independent, so [enabled] is the
 * only input.
 */
abstract class FG2DevFixesStepSpec : DevArtifactOnlyStepSpec {

    /** Whether the FG2 dev injections apply (profile-dependent). */
    @get:Input
    abstract val enabled: Property<Boolean>
}

class FG2DevFixesStepType : RemapStepType<FG2DevFixesStepSpec> {

    override val name = "unimined"
    override val stepName = "forge-fg2-fixes"
    override val specType: KClass<FG2DevFixesStepSpec> = FG2DevFixesStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: FG2DevFixesStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        if (!spec.enabled.getOrElse(false)) return@ArtifactStep input
        val workDir = Files.createDirectories(c.workDir.resolve("forge-fg2-fixes"))
        val output = workDir.resolve("fixed.jar")
        if (!Files.exists(output)) {
            val tmp = workDir.resolve("tmp.jar")
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            FileSystems.newFileSystem(tmp).use { fs ->
                FG2DevFixes.fix(fs)
            }
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING)
        }
        output
    }
}
