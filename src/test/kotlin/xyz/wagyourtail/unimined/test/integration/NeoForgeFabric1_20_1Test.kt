package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.util.runTestProject

class NeoForgeFabric1_20_1Test {
    @ParameterizedTest
    @MethodSource("xyz.wagyourtail.unimined.util.IntegrationTestUtils#versions")
    fun test_neoforge_fabric_1_20_1(gradleVersion: String) {
        try {
            val result = runTestProject("1.20.1-NeoForge-Fabric", gradleVersion)

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