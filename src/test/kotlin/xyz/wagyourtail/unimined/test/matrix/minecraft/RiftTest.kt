package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests functions related to using the Rift patcher.
 */
class RiftTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix(
			"1.13.2"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Rift"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_rift(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
