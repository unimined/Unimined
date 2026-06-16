package xyz.wagyourtail.unimined.test.matrix.reindev

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils

/**
 * Tests functions related to supporting ReIndev and FoxLoader,
 * such as the ReIndev game provider,
 * and the FoxLoader patcher,
 * as well as using Fabric Loader for ReIndev.
 */
class ReIndevTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		private val versions = IntegrationTestUtils.versionMatrix(
			"2.8.1_06",
		)
	}

	override val String.projectPath: String
		get() = "reindev/${this}-Fabric-FoxLoader"

	@ParameterizedTest(name = "[Gradle {0}] [ReIndev {1}]")
	@FieldSource("versions")
	fun test_reindev(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
