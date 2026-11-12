package xyz.wagyourtail.unimined.decompiler.vineflower

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.source.task.DecompileTask
import javax.inject.Inject

/**
 * Decompiles [inputJar] via Vineflower's `ConsoleDecompiler` in a forked
 * JVM — process isolation keeps decompiler memory pressure out of the
 * Gradle daemon (the same reason remapping is headed to workers).
 */
@DisableCachingByDefault
abstract class VineflowerDecompileTask : DecompileTask() {

    /** The resolved vineflower classpath (the `org.vineflower:vineflower`
     *  artifact plus its transitive deps). */
    @get:InputFiles
    @get:Classpath
    abstract val decompilerClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val execOperations: ExecOperations

    /** Toolchain-resolved `java` launcher — set by the engine when
     *  [javaVersion] is requested; the version property is the declared
     *  input, this file is its derived form. */
    @get:Internal
    abstract val javaLauncher: RegularFileProperty

    @TaskAction
    fun decompile() {
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        execOperations.javaexec { spec ->
            spec.classpath = decompilerClasspath
            spec.mainClass.set(
                "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
            )
            javaLauncher.orNull?.let {
                spec.executable = it.asFile.absolutePath
            }
            spec.maxHeapSize = "2g"
            spec.jvmArgs(jvmArgs.get())
            spec.args = options.get().map { (key, value) -> "-$key=$value" } +
                    args.get() +
                    listOf(inputJar.get().asFile.absolutePath, output.absolutePath)
        }
    }

}
