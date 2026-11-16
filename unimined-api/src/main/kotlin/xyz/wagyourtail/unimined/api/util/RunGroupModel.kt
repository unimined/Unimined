package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Cross-project run classpath-group model — one instance lives on the
 * `rootProject` as the `uniminedRunGroups` extension. Each project's
 * environment binding contributes its own
 * `projectPath:environmentId` entry at configuration time; consumers
 * read the model at execution, when every contribution is present
 * regardless of project evaluation order. This replaces the
 * `gradle.projectsEvaluated` recompute previously needed to see
 * sibling projects' source sets and loader specs — a lifecycle hook
 * configuration-cache builds should not need.
 *
 * Entries are providers so source-set outputs and loader specs stay
 * lazily resolved; the model itself serializes by capture into the
 * execution-time provider that reads it.
 */
open class RunGroupModel @javax.inject.Inject constructor() {

    /** One environment's contributed group data. */
    class Contribution(
        /** Absolute paths of the env's output dirs (resources +
         *  classes). */
        val dirs: Provider<List<String>>,
        /** The env's declared loader name (`""` when unset) — the
         *  consumer classifies family via `LoaderRegistry`. */
        val loaderName: Provider<String>,
    )

    /** `projectPath:environmentId` → that env's contribution. */
    val contributions = mutableMapOf<String, Contribution>()

    /** Register [project]'s env contribution. */
    fun contribute(
        project: Project,
        environmentId: String,
        dirs: Provider<List<String>>,
        loaderName: Provider<String>,
    ) {
        contributions["${project.path}:$environmentId"] =
            Contribution(dirs, loaderName)
    }

    /** Read one env's contribution (`null` when that env's project
     *  never contributed — e.g. it doesn't apply unimined). */
    fun contribution(projectPath: String, environmentId: String) =
        contributions["$projectPath:$environmentId"]

    companion object {

        /** The shared model — created on `rootProject` on first use. */
        fun of(project: Project): RunGroupModel {
            val extensions = project.rootProject.extensions
            return extensions.findByType(RunGroupModel::class.java)
                ?: extensions.create(
                    "uniminedRunGroups",
                    RunGroupModel::class.java)
        }
    }
}
