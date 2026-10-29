package xyz.wagyourtail.unimined.mod

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.util.*

/**
 * Loader metadata emitters discovered via `META-INF/services`. A fresh
 * registry at task execution sees the same emitters as the configuration-
 * time registry, keeping loader selection out of serialized state.
 */
class ModMetadataEmitterRegistry {

    private val emitters = linkedMapOf<String, ModMetadataEmitter>()

    init {
        val found = ServiceLoader.load(
            ModMetadataEmitter::class.java,
            ModMetadataEmitter::class.java.classLoader
        ).toList() + ServiceLoader.load(
            ModMetadataEmitter::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.forEach { register(it) }
    }

    /** Register an emitter implementation. */
    fun register(emitter: ModMetadataEmitter) {
        emitters.putIfAbsent(emitter.loader, emitter)
    }

    /** The emitter serving [loader], or a clear failure listing choices. */
    fun select(loader: String): ModMetadataEmitter = emitters[loader]
        ?: throw GradleException(
            "mod { loader(\"$loader\") }: no metadata emitter registered for " +
                    "loader '$loader' — apply its transform plugin " +
                    "(registered: ${emitters.keys.sorted().ifEmpty { listOf("none") }})"
        )

    /** Whether an emitter serves [loader]. */
    fun has(loader: String): Boolean = loader in emitters

}
