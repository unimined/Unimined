package xyz.wagyourtail.unimined.remapper.uml

import net.ornithemc.preen.Preen
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `preen` — Ornithe's obscure-obfuscation undoer
 * (`net.ornithemc.preen.Preen`), as wired by Ploceus.
 *
 * Ploceus's processor is exactly `Preen.modifyMergedBridgeMethodsAccess`:
 * detect methods already marked `ACC_BRIDGE` whose bytecode shape shows
 * that ProGuard merged the specialized implementation into the bridge,
 * then clear `ACC_FINAL`, `ACC_SYNTHETIC`, and `ACC_BRIDGE` on those
 * existing methods. It does **not** split methods, synthesize missing
 * bridges, or repair dangling call sites.
 *
 * Upstream preen rewrites the jar in place, while [ArtifactStep] must not
 * mutate its input — so this step first copies the artifact and applies
 * preen to the copy.
 */
class PreenStepType : RemapStepType<PreenStepSpec> {

    override val name = "ornithe-preen"
    override val stepName = "preen"
    override val specType = PreenStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: PreenStepSpec, ctx: StepContext) = ArtifactStep { input, sctx ->
        val out = sctx.workDir.resolve(
            input.fileName.toString().removeSuffix(".jar") + "-preen.jar"
        )
        Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
        Preen.modifyMergedBridgeMethodsAccess(out)
        out
    }
}
