package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.minecraft.spi.ProductProvider
import java.util.*

/**
 * Registry of [ProductProvider] impls supplied by provider plugins.
 *
 * Providers self-register via `META-INF/services/…ProductProvider` —
 * loaded automatically at construction with the SPI class's own
 * classloader (not the TCCL — transforms/workers isolate it) — and may
 * additionally call [register] programmatically.
 */
class ProductProviderRegistry {

    private val selector: SpiSelector<ProductProvider, ProductSpec> =
        spiSelector("minecraft provider")

    private val registeredNames = mutableListOf<String>()

    init {
        ServiceLoader.load(
            ProductProvider::class.java,
            ProductProvider::class.java.classLoader
        ).forEach(::register)
    }

    /** Register a provider impl. Duplicate names are rejected. */
    fun register(provider: ProductProvider) {
        selector.register(provider)
        registeredNames += provider.name
    }

    /** Names of all registered providers, in registration order. */
    fun registered(): List<String> = registeredNames.toList()

    /**
     * Negotiate the provider for [spec] — capability selection via
     * [ProductProvider.supports], or the DSL [override] name.
     */
    fun select(spec: ProductSpec, override: String? = null): ProductProvider =
        selector.select(spec, override)

}
