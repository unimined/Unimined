package xyz.wagyourtail.unimined.decompiler.vineflower

import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.source.task.DecompileTask

/**
 * Decompiles [inputJar] via Vineflower's `ConsoleDecompiler` in a forked
 * JVM — process isolation keeps decompiler memory pressure out of the
 * Gradle daemon (the same reason remapping is headed to workers).
 */
@CacheableTask
abstract class VineflowerDecompileTask : DecompileTask() {

    /** The resolved vineflower classpath (the `org.vineflower:vineflower`
     *  artifact plus its transitive deps). */
    @get:InputFiles
    @get:Classpath
    abstract val decompilerClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun decompile() {
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        execOperations.javaexec { spec ->
            spec.classpath = decompilerClasspath
            spec.mainClass.set(
                "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
            )
            spec.maxHeapSize = "2g"
            spec.args = options.get().map { (key, value) -> "-$key=$value" } +
                listOf(inputJar.get().asFile.absolutePath, output.absolutePath)
        }
    }

}
