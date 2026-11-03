package xyz.wagyourtail.unimined.transform.forge

import xyz.wagyourtail.unimined.transform.PipelinePreset
import xyz.wagyourtail.unimined.transform.PipelinePresetSource
import xyz.wagyourtail.unimined.transform.PipelineSpec
import xyz.wagyourtail.unimined.transform.jarmod.ChecksumPatchStepSpec
import xyz.wagyourtail.unimined.transform.jarmod.FG1FixStepSpec
import xyz.wagyourtail.unimined.transform.jarmod.JarModMergeStepSpec

/**
 * forge era presets — canonical step lists the `forge-era` artifact
 * probe selects between (declared `select("era")` pins override the
 * probe; mismatch fails at realization).
 *
 * `era("userdev")` — userdev-era forge (1.14+ userdev/FG3+): the
 * whole pipeline is one `forge:userdevExec` step since forge's
 * `config.json` already describes a complete pipeline (NFRT shape).
 *
 * `era("binpatches")` — legacy Binpatches-era forge (1.3-1.13):
 * binary patch pack applied onto the merged jar.
 */
object UserdevEraPreset : PipelinePreset {

    override val name = "forge-userdev"

    override fun expand(pipeline: PipelineSpec) {
        pipeline.step("forge:userdevExec", ForgeUserdevExecStepSpec::class.java) { }
    }

}

object BinpatchesEraPreset : PipelinePreset {

    override val name = "forge-binpatches"

    override fun expand(pipeline: PipelineSpec) {
        pipeline.step("forge:binpatches", ForgeBinpatchesStepSpec::class.java) { }
    }

}

/**
 * `era("forge-zipPatches")` — FG1-era jar-mod forge: the artifact is
 * a zip of patched classes merged into the MC jar. Composes jarmod's
 * step types rather than duplicating merge mechanics — the merge
 * reads env-bound jars via `SpecContext.envArtifacts("jarmod:merge")`,
 * supplied by forge's spec-context factory once ZipPatches jar
 * resolution lands (sided/`@zip` FG1 forms in
 * `ForgeLoaderResolution`). Until then the merge is a no-op and the
 * FG1 fix/checksum steps carry the era's behavior.
 */
object ZipPatchesEraPreset : PipelinePreset {

    override val name = "forge-zipPatches"

    override fun expand(pipeline: PipelineSpec) {
        pipeline.step("jarmod:merge", JarModMergeStepSpec::class.java) { }
        pipeline.step("jarmod:fg1Fix", FG1FixStepSpec::class.java) { }
        pipeline.step("jarmod:checksumPatch", ChecksumPatchStepSpec::class.java) { }
    }

}

/** ServiceLoader source — forge's era presets. */
class ForgePresetSource : PipelinePresetSource {

    override fun presets(): List<PipelinePreset> = listOf(
        UserdevEraPreset,
        BinpatchesEraPreset,
        ZipPatchesEraPreset,
    )

}
