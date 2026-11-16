package xyz.wagyourtail.unimined.runs.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `uniminedRuns` — prints the declared run configurations (one line
 * each: task, environment, side, type, main class). A diagnostics
 * surface for checking what `runs { }` materialized — NG's `runs`
 * report parity. Lines are computed lazily so run registrations from
 * later environment binding still appear.
 */
@DisableCachingByDefault(because = "diagnostics — reports declared " +
        "run configs resolved at execution; nothing to cache")
abstract class RunsReportTask : DefaultTask() {

    /** Pre-rendered report lines. */
    @get:Internal
    abstract val reportLines: ListProperty<String>

    @TaskAction
    fun report() {
        reportLines.get().forEach { println(it) }
    }
}
