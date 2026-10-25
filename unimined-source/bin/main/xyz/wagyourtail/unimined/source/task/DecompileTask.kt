package xyz.wagyourtail.unimined.source.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import xyz.wagyourtail.unimined.api.task.ProducesArtifacts

/**
 * Base task type for decompiler engines: produces a sources jar from a
 * dev artifact. Engine plugins subclass this with their decompiler's
 * invocation (usually a classloader-isolated worker action).
 */
@CacheableTask
abstract class DecompileTask : DefaultTask(), ProducesArtifacts {

    /** The jar to decompile — the decompile-surface fork, not the runtime jar. */
    @get:InputFile
    @get:Classpath
    abstract val inputJar: RegularFileProperty

    /** Classpath entries for reference resolution. */
    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** Engine-specific options. */
    @get:Input
    abstract val options: MapProperty<String, String>

    /** The produced sources jar. */
    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    override val artifacts: Map<String, Provider<RegularFile>>
        get() = mapOf("sources" to outputJar)

}
