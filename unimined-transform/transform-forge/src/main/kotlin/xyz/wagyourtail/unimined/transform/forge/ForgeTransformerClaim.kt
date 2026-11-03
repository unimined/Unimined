package xyz.wagyourtail.unimined.transform.forge

import xyz.wagyourtail.unimined.transform.CustomTransformer
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeEnvironmentWiring
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerSource

/**
 * forge-family transform claim — claims envs whose descriptor
 * `family` is `forge`, declares the family's feature bindings on
 * the env spec, and delegates era-specific wiring to
 * [ForgeEnvironmentWiring]. Era presets (`era("binpatches")`/
 * `era("userdev")` step lists + artifact probe) are the declared-
 * pipeline follow-up — the claim makes the family route through
 * the registry today so `custom` can compose its step types.
 */
class ForgeTransformerClaim : TransformerClaim {

    override val claimedFamilies = setOf("forge")

    override fun transformer(env: TransformerClaim.TransformerEnvContext)
            : MinecraftTransformer {
        env.env.features.binding("accessTransformer")
        env.env.features.binding("classTweaker")
        env.env.features.binding("mixin")
        env.env.features.binding("modDiscovery", "modClasses")

        env.minecraft?.let { ForgeEnvironmentWiring.wire(env.project, it) }

        return CustomTransformer(env)
    }

}

/** ServiceLoader source — forge's transform claim. */
class ForgeTransformerSource : TransformerSource {

    override fun transformerClaims(): List<TransformerClaim> =
        listOf(ForgeTransformerClaim())

}
