package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests functions related to using the upstream Fabric toolchain,
 * such as the Fabric patcher,
 * Fabric Intermediary provider, Yarn mappings provider
 */
class FabricMinecraftTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix(
			"1.14", "1.21.5"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Fabric"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_fabric(gradleVersion: String, minecraftVersion: String) {
		super.testDirectory(gradleVersion, minecraftVersion)
	}
}
