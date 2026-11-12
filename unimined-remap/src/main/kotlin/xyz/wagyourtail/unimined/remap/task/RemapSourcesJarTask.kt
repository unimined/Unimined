package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * Transforms a sources jar by running the declared
 * [RemapSurface.SOURCES] step pipeline over it (source remap, javadoc
 * enrichment, JST, ...).
 */
@DisableCachingByDefault
abstract class RemapSourcesJarTask : AbstractRemapTask(), ProducesArtifacts {

    init {
        surface.convention(RemapSurface.SOURCES)
    }

    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("sources" to outputArtifact)

}
