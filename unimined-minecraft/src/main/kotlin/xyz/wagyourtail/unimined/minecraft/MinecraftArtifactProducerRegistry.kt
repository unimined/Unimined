package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifactProducer
import java.util.ServiceLoader

/**
 * Registry of [MinecraftArtifactProducer] impls supplied by provider
 * plugins — mirrors [MinecraftProviderRegistry].
 *
 * Producers self-register via `META-INF/services/…MinecraftArtifactProducer`
 * — loaded automatically at construction with the SPI class's own
 * classloader (not the TCCL — transform isolation hides it) — and may
 * additionally call [register] programmatically. A fresh registry inside a
 * transform action rediscovers the same impls as configuration time.
 */
class MinecraftArtifactProducerRegistry {

    private val selector: SpiSelector<MinecraftArtifactProducer, MinecraftSpec> =
        spiSelector("minecraft artifact producer")

    init {
        val found = ServiceLoader.load(
            MinecraftArtifactProducer::class.java,
            MinecraftArtifactProducer::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MinecraftArtifactProducer::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(::register)
    }

    /** Register a producer impl. */
    fun register(producer: MinecraftArtifactProducer) {
        selector.register(producer)
    }

    /**
     * Negotiate the producer for [spec] — capability selection via
     * [MinecraftArtifactProducer.supports], or the DSL [override] name.
     */
    fun select(spec: MinecraftSpec, override: String? = null): MinecraftArtifactProducer =
        selector.select(spec, override)

}
