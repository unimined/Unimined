package xyz.wagyourtail.unimined.api.util

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.util.GradleVersion

/**
 * Gradle Problems API routing — warnings Unimined emits get a
 * `ProblemId` (`unimined/<id>`) so they surface in Gradle's problems
 * report, IDE integrations, and `build/reports/problems`, not only
 * the console.
 *
 * The API is incubating since Gradle 8.6 — the plugin must still run
 * on older Gradle in era fixtures, so the API-touching path lives in
 * an isolated object the JVM only loads past the version gate; below
 * the floor the warning is logger-only.
 */
object UniminedProblems {

    private val PROBLEMS_MIN = GradleVersion.version("8.6")

    /** Report a [severity]-level problem under `unimined/<id>`.
     *  Always logs too — the problems report is an opt-in surface;
     *  console readers still need the line. */
    fun report(
        logger: Logger,
        objects: ObjectFactory,
        id: String,
        summary: String,
        details: String? = null,
        severity: Severity = Severity.WARNING
    ) {
        if (GradleVersion.current().baseVersion >= PROBLEMS_MIN) {
            ProblemsApi.report(objects, id, summary, details, severity)
        }
        logger.warn(
            "[Unimined] $summary${details?.let { " — $it" } ?: ""}"
        )
    }

    /** [report] keyed off a [Project] — the common call shape. */
    fun report(
        logger: Logger,
        project: Project,
        id: String,
        summary: String,
        details: String? = null,
        severity: Severity = Severity.WARNING
    ) = report(logger, project.objects, id, summary, details, severity)

    /**
     * Report an ERROR-severity problem, then fail the build with a
     * [GradleException] carrying the same text — the problems report
     * gets a category/heading for the failure while the exception
     * still propagates exactly like a raw `error(...)` did.
     */
    fun fail(
        logger: Logger,
        objects: ObjectFactory,
        id: String,
        summary: String,
        details: String? = null
    ): Nothing {
        report(logger, objects, id, summary, details, Severity.ERROR)
        throw GradleException(
            "$summary${details?.let { " — $it" } ?: ""}")
    }

    /** [fail] keyed off a [Project] — the common call shape. */
    fun fail(
        logger: Logger,
        project: Project,
        id: String,
        summary: String,
        details: String? = null
    ): Nothing = fail(logger, project.objects, id, summary, details)

    enum class Severity { WARNING, ERROR }

    /** Isolated so its `org.gradle.api.problems` references only link
     *  on Gradle ≥ 8.6 — the class is never touched below the floor. */
    private object ProblemsApi {

        fun report(
            objects: ObjectFactory,
            id: String,
            summary: String,
            details: String?,
            severity: Severity
        ) {
            val problems = objects
                .newInstance(Holder::class.java).problems
            val sev = when (severity) {
                Severity.WARNING -> org.gradle.api.problems.Severity
                    .WARNING
                Severity.ERROR -> org.gradle.api.problems.Severity.ERROR
            }
            @Suppress("DEPRECATION")
            problems.reporter.report(
                org.gradle.api.problems.ProblemId.create(
                    "unimined/$id", summary,
                    // `others` root → namespaced second-level group;
                    // the 2-arg ProblemGroup.create is deprecated
                    problems.groups.others.group("unimined")
                )
            ) { spec ->
                spec.details(details ?: summary)
                    .severity(sev)
            }
        }

        /** Service-injection carrier — `Problems` isn't an extension;
         *  Gradle injects it into `objects.newInstance` classes. */
        abstract class Holder
        @javax.inject.Inject constructor() {
            @get:javax.inject.Inject
            abstract val problems: org.gradle.api.problems.Problems
        }
    }
}

/** Report a Gradle problem (`unimined/<id>`) — Problems API where the
 *  running Gradle supports it, plain `[Unimined]` warn otherwise.
 *  See [UniminedProblems.report]. */
fun Project.reportProblem(
    id: String,
    summary: String,
    details: String? = null,
    severity: UniminedProblems.Severity = UniminedProblems.Severity.WARNING,
    logger: Logger = this.logger
) = UniminedProblems.report(logger, this, id, summary, details, severity)

/** Report an ERROR-severity problem then throw it — replaces a raw
 *  `throw GradleException(...)` on user-facing failure paths so the
 *  failure also lands in the problems report under `unimined/<id>`.
 *  See [UniminedProblems.fail]. */
fun Project.failProblem(
    id: String,
    summary: String,
    details: String? = null,
    logger: Logger = this.logger
): Nothing = UniminedProblems.fail(logger, this, id, summary, details)
