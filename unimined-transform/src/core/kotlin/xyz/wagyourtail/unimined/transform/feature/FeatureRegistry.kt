package xyz.wagyourtail.unimined.transform.feature

import java.util.ServiceLoader

/**
 * Registry of [EnvironmentFeature] impls — ServiceLoader-discovered
 * [FeatureSource] providers (loader feature packs) plus
 * `features.register(key, impl)` additions. Capability bindings on
 * descriptor rows / `features { }` DSL resolve through here; an
 * unresolvable key fails at selection time, not silently.
 */
class FeatureRegistry {

    private val registered = mutableListOf<EnvironmentFeature>()

    /** Register a feature impl — later registration shadows an earlier
     *  impl with the same [EnvironmentFeature.id] (buildSrc/plugin
     *  override of a bundled impl is legitimate). */
    fun register(feature: EnvironmentFeature) {
        registered.removeAll { it.id == feature.id }
        registered += feature
    }

    /** Every known feature, builtins first, in registration order. */
    val all: List<EnvironmentFeature>
        get() = builtins() + registered

    /** Resolve the impl for binding key [id], or null. */
    fun resolve(id: String): EnvironmentFeature? = all.lastOrNull { it.id == id }

    /** Resolve [id] or fail at selection time with a clear error. */
    fun require(id: String): EnvironmentFeature =
        resolve(id) ?: throw IllegalArgumentException(
            "no EnvironmentFeature registered for binding '$id' — " +
                    "bundled keys: ${all.joinToString { it.id }}; " +
                    "custom keys need features.register(key, impl)"
        )

    companion object {

        /** ServiceLoader scan over [FeatureSource] providers. */
        fun builtins(): List<EnvironmentFeature> =
            (ServiceLoader.load(
                FeatureSource::class.java,
                FeatureSource::class.java.classLoader
            ) + runCatching {
                ServiceLoader.load(FeatureSource::class.java)
            }.getOrDefault(emptyList()))
                .flatMap { it.features() }

    }

}

/** ServiceLoader SPI — loader modules expose their feature impls here. */
interface FeatureSource {

    fun features(): List<EnvironmentFeature>

}
