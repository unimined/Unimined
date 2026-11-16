package xyz.wagyourtail.unimined.api.util

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * [reportProblem] smoke coverage — on the build's Gradle (9.x) the
 * Problems API path runs end-to-end (service injection + reporter),
 * so a broken impl or API drift throws here rather than at a user's
 * build. The logger fallback always emits regardless.
 */
class UniminedProblemsTest {

    @Test
    fun `warn and error severities route without throwing`() {
        val p = ProjectBuilder.builder().build()
        assertDoesNotThrow {
            p.reportProblem(
                "test/route", "summary text", "detail text"
            )
            p.reportProblem(
                "test/err", "error summary",
                severity = UniminedProblems.Severity.ERROR
            )
        }
    }

    /**
     * [failProblem] still throws after reporting — converted failure
     * sites must not lose their fail-the-build semantics.
     */
    @Test
    fun `failProblem reports then throws GradleException`() {
        val p = ProjectBuilder.builder().build()
        val e = org.junit.jupiter.api.assertThrows<GradleException> {
            p.failProblem("test/fail", "fail summary", "fail detail")
        }
        assert(e.message!!.contains("fail summary")) {
            "exception should carry the summary: ${e.message}"
        }
        assert(e.message!!.contains("fail detail")) {
            "exception should carry the details: ${e.message}"
        }
    }
}
