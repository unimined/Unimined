package xyz.wagyourtail.unimined.api.spi

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * IDE-sync hook registry: transform/provider plugins contribute
 * tasks that `unimined-ide` rides under the `genIdeRuns` umbrella —
 * which is appended to IntelliJ Gradle syncs (`idea.sync.active`),
 * so contributed tasks regenerate during sync.
 *
 * Contract: contributed tasks MUST be cheap file emitters — they
 * run on every IDE sync, so they must never resolve configurations
 * or produce jars.
 */
object IdeSyncTasks {

    private const val KEY = "unimined.ideSyncTasks"

    // stored on the project's extra properties: per-instance
    // (Gradle Project equality is path-based — a weak map keyed on
    // it would alias distinct roots), dies with the project, and
    // the extension map is itself thread-safe
    @Suppress("UNCHECKED_CAST")
    private fun list(
        project: Project
    ): MutableList<TaskProvider<out Task>> =
        project.extensions.extraProperties.let { ext ->
            synchronized(ext) {
                if (!ext.has(KEY)) {
                    ext.set(KEY, mutableListOf<TaskProvider<out Task>>())
                }
                ext.get(KEY) as MutableList<TaskProvider<out Task>>
            }
        }

    /** Queue [task] to run as part of `genIdeRuns` in [project]. */
    fun contribute(project: Project, task: TaskProvider<out Task>) {
        list(project) += task
    }

    /** Every task queued for [project] — `unimined-ide` consumption. */
    fun contributions(project: Project): List<TaskProvider<out Task>> =
        synchronized(project.extensions.extraProperties) {
            list(project).toList()
        }

}
