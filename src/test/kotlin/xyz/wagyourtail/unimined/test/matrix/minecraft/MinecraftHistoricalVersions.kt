package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils

/**
 * Minimal test to ensure features for supporting
 * historical versions only found on OmniArchive
 * such as `in-20091223-1459` are functioning correctly.
 */
class MinecraftHistoricalVersions : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = IntegrationTestUtils.versionMatrix(
			"in-20091223-1459",
		)
	}

	override val String.projectPath: String
		get() = this

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_indev(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
