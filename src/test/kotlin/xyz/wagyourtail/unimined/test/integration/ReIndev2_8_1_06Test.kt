package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.util.runTestProject

class ReIndev2_8_1_06Test {
    @ParameterizedTest
    @MethodSource("xyz.wagyourtail.unimined.util.IntegrationTestUtils#versions")
    fun test_reindev_2_8_1_06(gradleVersion: String) {
        try {
            val result = runTestProject("reindev/2.8.1_06-Fabric-FoxLoader", gradleVersion)

            try {
                result.task(":build")?.outcome?.let {
                    if (it != TaskOutcome.SUCCESS) throw Exception("build failed")
                } ?: throw Exception("build failed")
            } catch (e: Exception) {
                println(result.output)
                throw Exception(e)
            }
        } catch (e: UnexpectedBuildFailure) {
            println(e)
            throw Exception("build failed", e)
        }
    }
}
