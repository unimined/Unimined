package xyz.wagyourtail.unimined.test.matrix

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import xyz.wagyourtail.unimined.util.runTestProject

interface VersionMatrixTest {
	val String.projectPath: String

	@Throws(Exception::class)
	fun testDirectory(gradleVersion: String, gameVersion: String) {
		try {
			val result = runTestProject(gameVersion.projectPath, gradleVersion)

			try {
				result.task(":build")?.outcome?.let {
					if (it != TaskOutcome.SUCCESS) throw Exception("build failed")
				} ?: throw Exception("build did not run")
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
