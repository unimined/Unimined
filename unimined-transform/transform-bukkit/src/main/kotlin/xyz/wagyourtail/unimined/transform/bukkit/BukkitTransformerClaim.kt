package xyz.wagyourtail.unimined.transform.bukkit

import xyz.wagyourtail.unimined.transform.CustomTransformer
import xyz.wagyourtail.unimined.transform.bukkit.internal.BukkitEnvironmentWiring
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerSource

/**
 * bukkit-family transform claim — claims envs whose descriptor
 * `family` is `bukkit` (craftbukkit/spigot/paper rows), declares
 * classpath mod discovery (plugins ride the classpath plugin
 * loader), and delegates dist/patch/run wiring to
 * [BukkitEnvironmentWiring].
 */
class BukkitTransformerClaim : TransformerClaim {

    override val claimedFamilies = setOf("bukkit")

    override fun transformer(env: TransformerClaim.TransformerEnvContext)
            : MinecraftTransformer {
        env.env.features.binding("modDiscovery", "classpath")

        env.minecraft?.let { BukkitEnvironmentWiring.wire(env.project, it) }

        return CustomTransformer(env)
    }

}

/** ServiceLoader source — bukkit's transform claim. */
class BukkitTransformerSource : TransformerSource {

    override fun transformerClaims(): List<TransformerClaim> =
        listOf(BukkitTransformerClaim())

}
