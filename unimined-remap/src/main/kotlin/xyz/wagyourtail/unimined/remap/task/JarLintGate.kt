package xyz.wagyourtail.unimined.remap.task

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.remap.FootgunCheckLevel
import xyz.wagyourtail.unimined.remap.internal.JarLint
import java.nio.file.Path

/**
 * Shared production-jar finish for [RemapJarTask] and
 * [AbstractRemapTask]: stamp the produced-namespace marker (post-
 * pipeline so step impls can't strip it), then run [JarLint] and
 * act on the findings per [level].
 */
internal fun stampAndLintProductionJar(
    jar: Path, namespace: String, level: FootgunCheckLevel,
    logger: Logger, host: String
) {
    JarLint.stampNamespace(jar, namespace)
    if (level == FootgunCheckLevel.OFF) return
    val problems = JarLint.lint(jar, namespace)
    if (problems.isEmpty()) return
    when (level) {
        FootgunCheckLevel.WARN -> problems.forEach {
            logger.warn("$host: $it")
        }
        FootgunCheckLevel.ERROR -> throw GradleException(
            "$host: production jar failed footgun checks " +
                    "(${problems.size} problem${if (problems.size == 1) "" else "s"}):\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n(set remap.footgunChecks = WARN to downgrade, " +
                    "OFF to disable)"
        )
        FootgunCheckLevel.OFF -> {}
    }
}
