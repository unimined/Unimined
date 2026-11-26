package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.spec.CheckLevel
import xyz.wagyourtail.unimined.api.util.UniminedProblems
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.remap.internal.JarLint
import java.nio.file.Path

/**
 * Shared production-jar finish for [RemapJarTask] and
 * [AbstractRemapTask]: stamp the produced-namespace marker (post-
 * pipeline so step impls can't strip it), then run [JarLint] and
 * act on the findings per [level].
 */
internal fun stampAndLintProductionJar(
    jar: Path, namespace: String, level: CheckLevel,
    project: Project, logger: Logger, host: String
) {
    JarLint.stampNamespace(jar, namespace)
    if (level == CheckLevel.OFF) return
    val problems = JarLint.lint(jar, namespace)
    if (problems.isEmpty()) return
    when (level) {
        CheckLevel.WARN -> problems.forEach {
            project.reportProblem("remap/jar-lint", "$host: $it")
        }
        CheckLevel.ERROR -> UniminedProblems.fail(
            logger, project,
            "remap/jar-lint",
            "$host: production jar failed packaging lint " +
                    "(${problems.size} problem${if (problems.size == 1) "" else "s"})",
            problems.joinToString("\n") { "  - $it" } +
                    "\n(set remap.checks.jarLint = WARN to downgrade, " +
                    "OFF to disable)"
        )
        CheckLevel.OFF -> {}
    }
}
