package xyz.wagyourtail.unimined.api.task

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal

/**
 * Task-facing API surface: exposes a task's produced artifacts as named
 * [Provider]s so downstream plugins and other transforms can wire
 * task-dependency edges without depending on the concrete task type.
 */
interface ProducesArtifacts {

    /** Produced artifacts keyed by their role name
     *  (e.g. `"jar"`, `"sources"`, `"merged"`).
     *  Wiring accessor — task inputs/outputs are declared separately. */
    @get:Internal
    val artifacts: Map<String, Provider<RegularFile>>

}
