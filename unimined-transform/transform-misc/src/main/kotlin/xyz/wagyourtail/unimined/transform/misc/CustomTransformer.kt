package xyz.wagyourtail.unimined.transform.misc

import xyz.wagyourtail.unimined.transform.CustomTransformer
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim

/** Fallback claim — `custom` wins only when nothing else claims. */
class CustomTransformerClaim : TransformerClaim {

    override val fallbackClaim: Boolean = true

    override fun transformer(env: TransformerClaim.TransformerEnvContext)
            : MinecraftTransformer = CustomTransformer(env)

}
