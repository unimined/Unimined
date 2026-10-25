package xyz.wagyourtail.unimined.transform.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts

/**
 * Base task type for Minecraft transforms: takes the provider's named
 * artifact and produces the transformed dev artifact.
 *
 * Concrete transform work is contributed by `unimined-transform-*`
 * plugins; this shell defines the input/output contract so downstream
 * plugins can wire dependency edges via [ProducesArtifacts].
 */
@CacheableTask
abstract class TransformMinecraftTask : DefaultTask(), ProducesArtifacts {

    /** The named-namespace artifact from the provider (or prior transform). */
    @get:InputFile
    @get:Classpath
    abstract val inputArtifact: RegularFileProperty

    /** Where the transformed dev artifact is written. */
    @get:OutputFile
    abstract val outputArtifact: RegularFileProperty

    @get:Internal
    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("jar" to outputArtifact)

}
