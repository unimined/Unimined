package xyz.wagyourtail.unimined.runs

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Stages the run directory before a [MinecraftRunTask] executes — loader
 * envs contribute staging via `prepareTaskMutators` (dyn-lib copies,
 * mod-dir seeding, metadata files). Named task so staging is visible in
 * the task graph instead of buried in the run task's `doFirst`.
 */
@DisableCachingByDefault(because = "run-dir staging is fast and env-local")
abstract class PrepareRunTask : DefaultTask() {

    /** The run directory this task materializes (mirrors the run task's). */
    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    init {
        group = "unimined"
    }

    @TaskAction
    fun prepare() {
        runDirectory.get().asFile.mkdirs()
    }

}
