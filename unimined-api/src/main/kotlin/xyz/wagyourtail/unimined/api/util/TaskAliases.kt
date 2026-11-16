package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * Task-name deprecation bridge. Gradle has no rename API, so the old
 * name registers as a lifecycle task that warns on invocation and
 * depends on the canonical task — `gradlew oldName` still runs the
 * real work (after its own task finishes, since task actions always
 * run last). Registration stays lazy like any `tasks.register`.
 */
fun Project.registerDeprecatedTaskAlias(
    deprecatedName: String,
    canonical: TaskProvider<out Task>,
): TaskProvider<Task> = tasks.register(deprecatedName) { t ->
    t.group = "unimined"
    t.description = "Deprecated alias for '${canonical.name}'"
    t.dependsOn(canonical)
    t.doLast {
        t.logger.warn(
            "unimined: task '$deprecatedName' is deprecated — " +
                    "use '${canonical.name}' instead")
    }
}
