package xyz.wagyourtail.unimined.publish.task

import org.gradle.api.DefaultTask
import xyz.wagyourtail.unimined.api.util.failProblem
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Execution-time Maven-coordinate uniqueness check for the
 * `unimined<Env>` publication set — the replacement for the old
 * `projectsEvaluated` pass. Two publications resolving to the same
 * `groupId:artifactId:version` would overwrite each other on the
 * maven; the failure only needs to fire when publishing actually
 * runs, so publish-family tasks depend on this check.
 *
 * [publicationCoordinates] carries `name=group:artifact:version`
 * entries computed by a configuration-time provider (publication
 * coordinates stay user-customizable until evaluation completes,
 * which is exactly when the provider resolves).
 */
@DisableCachingByDefault(
    because = "coordinate validation is cheap and must re-run whenever a publish runs"
)
abstract class CheckUniminedPublicationsTask : DefaultTask() {

    /** `name=groupId:artifactId:version` per unimined publication. */
    @get:Input
    abstract val publicationCoordinates: ListProperty<String>

    @TaskAction
    fun check() {
        val entries = publicationCoordinates.get()
        val dupes = entries.groupBy { it.substringAfter('=') }
            .filterValues { it.size > 1 }
        if (dupes.isEmpty()) return
        val detail = dupes.entries.joinToString("; ") { (coord, pubs) ->
            "${pubs.joinToString(", ") { it.substringBefore('=') }} -> $coord"
        }
        project.failProblem(
            "publish/duplicate-coordinates",
            "unimined-publish: multiple publications resolve to the " +
                "same coordinates ($detail)",
            "set a distinct artifactId per environment via " +
                "uniminedPublish { environment(\"<env>\") { " +
                "artifactId = \"...\" } }"
        )
    }
}
