package xyz.wagyourtail.unimined.transform.forge

import xyz.wagyourtail.unimined.transform.PipelinePreset
import xyz.wagyourtail.unimined.transform.PipelinePresetSource
import xyz.wagyourtail.unimined.transform.PipelineSpec

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

/** ServiceLoader source — forge's era presets. */
class ForgePresetSource : PipelinePresetSource {

    override fun presets(): List<PipelinePreset> = listOf(
        UserdevEraPreset,
        BinpatchesEraPreset,
    )

}
