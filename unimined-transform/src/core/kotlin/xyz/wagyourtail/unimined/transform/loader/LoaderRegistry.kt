package xyz.wagyourtail.unimined.transform.loader

import java.util.*

/**
 * Name-keyed registry of [LoaderDescriptor] products —
 * ServiceLoader-discovered [LoaderDescriptorSource] providers plus
 * manually registered descriptors (buildSrc/custom loaders).
 */
class LoaderRegistry {

    private val registered = mutableListOf<LoaderDescriptor>()

    /** Register a descriptor instance (appended after builtins). */
    fun register(descriptor: LoaderDescriptor) {
        registered += descriptor
    }

    /** Every known descriptor, builtins first, in registration order. */
    val all: List<LoaderDescriptor>
        get() = builtins() + registered

    /** Find the product named [id], or `null`. */
    fun resolve(id: String): LoaderDescriptor? = all.firstOrNull { it.id == id }

    /** All descriptors a transform [family] claims. */
    fun family(family: String): List<LoaderDescriptor> =
        all.filter { it.family == family }

    companion object {

        /** ServiceLoader scan over [LoaderDescriptorSource] providers. */
        fun builtins(): List<LoaderDescriptor> =
        // ServiceLoader.load() with no explicit loader uses the
        // *thread context* classloader — inside provider bodies
        // evaluated during dependency resolution Gradle swaps in
        // an instrumented visiting loader that sees no service
        // files. The SPI class's own loader is the plugin
        // classpath; TCCL is kept as a fallback for hosts that
            // swap it.
            (ServiceLoader.load(
                LoaderDescriptorSource::class.java,
                LoaderDescriptorSource::class.java.classLoader
            ).toList() + ServiceLoader.load(
                LoaderDescriptorSource::class.java,
                Thread.currentThread().contextClassLoader
            ).toList())
                .distinctBy { it::class.java.name }
                .flatMap { it.descriptors() }

        /** Resolve [id] over builtins only. */
        fun resolveBuiltin(id: String): LoaderDescriptor? =
            builtins().firstOrNull { it.id == id }

    }

}

/** SPI: a transform plugin declares its loader products as data. */
interface LoaderDescriptorSource {

    /** The descriptors this provider contributes. */
    fun descriptors(): List<LoaderDescriptor>

}
