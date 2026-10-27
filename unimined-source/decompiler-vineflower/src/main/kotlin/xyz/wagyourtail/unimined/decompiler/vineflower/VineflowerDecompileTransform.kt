package xyz.wagyourtail.unimined.decompiler.vineflower

import javax.inject.Inject
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.process.ExecOperations

/**
 * Per-artifact decompile for `genModSources`: converts a dev-namespace
 * mod jar into a sources jar via Vineflower's `ConsoleDecompiler` in a
 * forked JVM — process isolation keeps decompiler memory pressure out
 * of the Gradle daemon, matching [VineflowerDecompileTask].
 *
 * Runs inside Gradle's transform execution — cacheable, parallel, and
 * fired by any artifact resolution including IDE sync.
 */
@CacheableTransform
abstract class VineflowerDecompileTransform @Inject constructor(
    private val execOperations: ExecOperations
) : TransformAction<VineflowerDecompileTransform.Params> {

    interface Params : TransformParameters {

        /** Decompiler classpath — the `org.vineflower:vineflower`
         *  artifact plus its transitive deps. */
        @get:InputFiles
        @get:Classpath
        val decompilerClasspath: ConfigurableFileCollection

        /** Classpath entries for reference resolution (`-e=` flags). */
        @get:InputFiles
        @get:Classpath
        val referenceClasspath: ConfigurableFileCollection

        /** Engine-specific options (`-$key=$value` flags). */
        @get:Input
        val options: MapProperty<String, String>

    }

    @get:InputArtifact
    @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val output = outputs.file("${input.nameWithoutExtension}-sources.jar")
        output.parentFile.mkdirs()
        execOperations.javaexec { spec ->
            spec.classpath = parameters.decompilerClasspath
            spec.mainClass.set(
                "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
            )
            spec.maxHeapSize = "2g"
            spec.args = parameters.options.get().map { (key, value) -> "-$key=$value" } +
                parameters.referenceClasspath.files.map { "-e=${it.absolutePath}" } +
                listOf(input.absolutePath, output.absolutePath)
        }
    }

}
