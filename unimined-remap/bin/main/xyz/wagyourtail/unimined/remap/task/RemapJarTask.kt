package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * Remaps a binary artifact between namespaces by running the declared
 * [RemapSurface.JAR] step pipeline over it.
 */
@CacheableTask
abstract class RemapJarTask : AbstractRemapTask(), ProducesArtifacts {

    init {
        surface.convention(RemapSurface.JAR)
    }

    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("jar" to outputArtifact)

}
