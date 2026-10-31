package xyz.wagyourtail.unimined.transform.fabric

import xyz.wagyourtail.unimined.transform.fabric.internal.FabricEnvironmentWiring
import xyz.wagyourtail.unimined.transform.CustomTransformer
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerSource

/**
 * fabric-family transform claim — the declared-pipeline form of the
 * plugin's loader gating. Claims envs whose descriptor `family` is
 * `fabric`, wires them through the existing environment wiring, and
 * declares the family's feature bindings on the env spec so the
 * binding set is inspectable/composable (a `custom` env with the
 * same bindings + descriptor row gets the same feature pack).
 */
class FabricTransformerClaim : TransformerClaim {

    override val claimedFamilies = setOf("fabric")

    override fun transformer(env: TransformerClaim.TransformerEnvContext)
            : MinecraftTransformer {
        // declared bindings — fabric's stock feature pack
        env.env.features.binding("mixin")
        env.env.features.binding("accessWidener")
        env.env.features.binding("interfaceInjection")
        env.env.features.binding("nestedJars", "fabric")
        env.env.features.binding("modDiscovery", "dli")
        env.env.features.binding("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient")

        // env-level wiring (loader dep, intermediary hop, AW steps,
        // Knot launch) — consumed at environment binding
        env.minecraft?.let { FabricEnvironmentWiring.wire(env.project, it) }

        return CustomTransformer(env)
    }

}

/** ServiceLoader source — fabric's transform claim. */
class FabricTransformerSource : TransformerSource {

    override fun transformerClaims(): List<TransformerClaim> =
        listOf(FabricTransformerClaim())

}
