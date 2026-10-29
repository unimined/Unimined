package xyz.wagyourtail.unimined.source

import xyz.wagyourtail.unimined.api.spi.SpiSelector
import xyz.wagyourtail.unimined.api.spi.spiSelector
import xyz.wagyourtail.unimined.source.spi.DecompileSpec
import xyz.wagyourtail.unimined.source.spi.DecompilerEngine
import java.util.*

/**
 * Registry of [DecompilerEngine] implementations supplied by
 * `unimined-decompiler-*` plugins.
 *
 * Engines self-register via
 * `META-INF/services/xyz.wagyourtail.unimined.source.spi.DecompilerEngine`
 * — loaded automatically at construction — and may additionally call
 * [register] programmatically. Selection uses the shared machinery —
 * capability + `Supports.priority`, with a DSL `engine` override.
 */
class DecompilerRegistry {

    private val selector: SpiSelector<DecompilerEngine, DecompileSpec> =
        spiSelector("decompiler engine")

    init {
        // ServiceLoader.load() with no explicit loader uses the *thread
        // context* classloader — under worker isolation that loader sees
        // nothing. The SPI class's own loader is the plugin classpath;
        // TCCL is kept as a fallback for hosts that swap it.
        val found = ServiceLoader.load(
            DecompilerEngine::class.java,
            DecompilerEngine::class.java.classLoader
        ).toList() + ServiceLoader.load(
            DecompilerEngine::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(selector::register)
    }

    /** Register an engine impl. */
    fun register(engine: DecompilerEngine) = selector.register(engine)

    /** Resolve the engine for [spec], honoring an optional [override]. */
    fun select(spec: DecompileSpec, override: String? = null): DecompilerEngine =
        selector.select(spec, override)

}
