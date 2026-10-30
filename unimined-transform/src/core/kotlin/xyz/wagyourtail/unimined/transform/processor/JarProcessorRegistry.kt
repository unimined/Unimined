package xyz.wagyourtail.unimined.transform.processor

import java.util.*

/**
 * Name-keyed registry of [JarProcessor] impls — ServiceLoader-discovered
 * builtins plus manually registered instances (custom transforms). The
 * `jar-processors` pipeline step resolves payloads through this at
 * execution, so impls must live on the plugin classpath.
 */
class JarProcessorRegistry {

    private val registered = mutableListOf<JarProcessor>()

    /** Register a processor instance (appended after builtins). */
    fun register(processor: JarProcessor) {
        registered += processor
    }

    /** Every known processor, builtins first, in registration order. */
    val all: List<JarProcessor>
        get() = builtins() + registered

    /** Find the impl named [name], or `null`. */
    fun resolve(name: String): JarProcessor? = all.firstOrNull { it.name == name }

    companion object {

        /** ServiceLoader scan — the static lookup used at execution.
         *  Explicit classloader: the TCCL during dependency resolution
         *  is Gradle's instrumented visiting loader, which sees no
         *  service files. */
        fun builtins(): List<JarProcessor> =
            (ServiceLoader.load(
                JarProcessor::class.java,
                JarProcessor::class.java.classLoader
            ).toList() + ServiceLoader.load(
                JarProcessor::class.java,
                Thread.currentThread().contextClassLoader
            ).toList()).distinctBy { it::class.java.name }

        /** Resolve [name] over builtins only — execution-side lookup. */
        fun resolveBuiltin(name: String): JarProcessor? =
            builtins().firstOrNull { it.name == name }

    }

}
