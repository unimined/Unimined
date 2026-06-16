package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.util.IntegrationTestUtils
import xyz.wagyourtail.unimined.util.runTestProject

class ForgeModloaderTest {
	companion object {
		@JvmStatic
		val versions = IntegrationTestUtils.versionMatrix(
			"1.2.5",
		)
	}

	@ParameterizedTest(name = "[Gradle {0}] [Forge / ModLoader] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_forge_modloader(gradleVersion: String, minecraftVersion: String) {
		try {
			val result = runTestProject("$minecraftVersion-Forge-Modloader", gradleVersion)

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
