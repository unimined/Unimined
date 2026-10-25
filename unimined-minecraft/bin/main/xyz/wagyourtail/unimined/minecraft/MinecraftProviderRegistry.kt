package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftProvider
import java.util.ServiceLoader

/**
 * Registry of [MinecraftProvider] impls supplied by provider plugins.
 *
 * Providers self-register via `META-INF/services/…MinecraftProvider` —
 * loaded automatically at construction with the SPI class's own
 * classloader (not the TCCL — transforms/workers isolate it) — and may
 * additionally call [register] programmatically.
 */
class MinecraftProviderRegistry {

    private val selector: SpiSelector<MinecraftProvider, MinecraftSpec> =
        spiSelector("minecraft provider")

    private val registeredNames = mutableListOf<String>()

    init {
        ServiceLoader.load(
            MinecraftProvider::class.java,
            MinecraftProvider::class.java.classLoader
        ).forEach(::register)
    }

    /** Register a provider impl. Duplicate names are rejected. */
    fun register(provider: MinecraftProvider) {
        selector.register(provider)
        registeredNames += provider.name
    }

    /** Names of all registered providers, in registration order. */
    fun registered(): List<String> = registeredNames.toList()

    /**
     * Negotiate the provider for [spec] — capability selection via
     * [MinecraftProvider.supports], or the DSL [override] name.
     */
    fun select(spec: MinecraftSpec, override: String? = null): MinecraftProvider =
        selector.select(spec, override)

}
