package xyz.wagyourtail.unimined.transform.forge.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Writes a run's legacy classpath file (`classpath-<env>-<run>.txt`
 * under `.gradle/unimined/forge/`) — the `{minecraft_classpath_file}`
 * substitution and `-DlegacyClassPath` sysprop read this file at
 * launch, so producing it as a task makes the write up-to-date
 * checkable and the run task's dependency explicit (1.x wrote it as a
 * mutator side effect).
 *
 * The newline-joined absolute paths are the FML dev-launch contract;
 * `-DlegacyClassPath` converts to `File.pathSeparator` at consumption.
 */
@DisableCachingByDefault
abstract class WriteRunClasspath @Inject constructor() : DefaultTask() {

    /** MC libraries + the run's selected Minecraft view (dev, prod, or
     *  srg-runtime) — the view is chosen lazily so post-DSL run state
     *  (`prod`, srg-runtime step declarations) still counts. */
    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            classpath.files.joinToString("\n") { it.absolutePath })
    }
}
