package xyz.wagyourtail.unimined.minecraft.spi

import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spi.Supports

/**
 * SPI implemented by each MC provider plugin (`provider-native`,
 * `provider-mavenizer`, `provider-nfrt`, third parties).
 *
 * Providers declare `supports(ProductSpec)`; the host negotiates per
 * environment with a DSL override and an ambiguity error via the shared
 * selection machinery.
 */
interface ProductProvider : Supports<ProductSpec> {

    /**
     * Materialize the environment's artifacts into the local maven cache
     * under synthetic coordinates (see the IDE-integration design) and
     * return the output contract. Everything about *how* artifacts come
     * to exist is provider-internal.
     */
    fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts

}
