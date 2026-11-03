package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts

/**
 * Remaps an arbitrary artifact between namespaces by running a declared
 * [xyz.wagyourtail.unimined.remap.spi.RemapSurface] step pipeline over
 * [inputArtifact] into [outputArtifact] — the generic input→output
 * sibling of [RemapJarTask] (which assembles its own jar from a
 * mirrored `CopySpec` instead). Used for surfaces whose input is
 * produced by another task — the decompile fork, the sources-surface
 * remap of a generated sources jar.
 */
@CacheableTask
abstract class RemapArtifactTask : AbstractRemapTask(), ProducesArtifacts {

    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("jar" to outputArtifact)

}
