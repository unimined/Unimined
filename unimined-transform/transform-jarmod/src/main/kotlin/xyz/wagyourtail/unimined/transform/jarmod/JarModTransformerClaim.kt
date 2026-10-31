package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.transform.CustomTransformer
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvironmentWiring
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerSource

/**
 * jarmod-family transform claim — claims envs whose descriptor
 * `family` is `jarmod`, declares classpath mod discovery (jar mods
 * and legacy modloaders scan the classpath), and delegates env
 * wiring to [JarModEnvironmentWiring]. The family's step types
 * (`jarmod:merge`/`jarmod:fg1Fix`/`jarmod:checksumPatch`) are
 * ServiceLoader'd separately — `pipeline { }` composes them.
 */
class JarModTransformerClaim : TransformerClaim {

    override val claimedFamilies = setOf("jarmod")

    override fun transformer(env: TransformerClaim.TransformerEnvContext)
            : MinecraftTransformer {
        env.env.features.binding("modDiscovery", "classpath")

        env.minecraft?.let {
            JarModEnvironmentWiring.createConfigurations(env.project, it)
            JarModEnvironmentWiring.wire(env.project, it)
        }

        return CustomTransformer(env)
    }

}

/** ServiceLoader source — jarmod's transform claim. */
class JarModTransformerSource : TransformerSource {

    override fun transformerClaims(): List<TransformerClaim> =
        listOf(JarModTransformerClaim())

}
