package xyz.wagyourtail.unimined.test.matrix.bta

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils

/**
 * Tests functions related to Better Than Adventure support,
 * such as the Better Than Adventure game provider.
 */
class BetterThanAdventureFabricTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		private val versions = IntegrationTestUtils.versionMatrix(
			"7.3_03",
		)
	}

	override val String.projectPath: String
		get() = "bta/$this-Fabric"

	@ParameterizedTest(name = "[Gradle {0}] [Better Than Adventure {1}]")
	@FieldSource("versions")
	fun test_bta(btaVersion: String, gradleVersion: String) =
		super.testDirectory(btaVersion, gradleVersion)
}
