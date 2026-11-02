package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.util.*

/**
 * Registry of [ProductArtifactProducer] impls supplied by provider
 * plugins — mirrors [ProductProviderRegistry].
 *
 * Producers self-register via `META-INF/services/…ProductArtifactProducer`
 * — loaded automatically at construction with the SPI class's own
 * classloader (not the TCCL — transform isolation hides it) — and may
 * additionally call [register] programmatically. A fresh registry inside a
 * transform action rediscovers the same impls as configuration time.
 */
class ProductArtifactProducerRegistry {

    private val selector: SpiSelector<ProductArtifactProducer, ProductSpec> =
        spiSelector("minecraft artifact producer")

    init {
        val found = ServiceLoader.load(
            ProductArtifactProducer::class.java,
            ProductArtifactProducer::class.java.classLoader
        ).toList() + ServiceLoader.load(
            ProductArtifactProducer::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(::register)
    }

    /** Register a producer impl. */
    fun register(producer: ProductArtifactProducer) {
        selector.register(producer)
    }

    /**
     * Negotiate the producer for [spec] — capability selection via
     * [ProductArtifactProducer.supports], or the DSL [override] name.
     */
    fun select(spec: ProductSpec, override: String? = null): ProductArtifactProducer =
        selector.select(spec, override)

}
