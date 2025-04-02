package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.util.runTestProject

class BabricModloaderB1_7_3Test {
    @ParameterizedTest
    @MethodSource("xyz.wagyourtail.unimined.util.IntegrationTestUtils#versions")
    fun test_babric_modloader_b1_7_3(gradleVersion: String) {
        try {
            val result = runTestProject("b1.7.3-Babric-Modloader", gradleVersion)

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