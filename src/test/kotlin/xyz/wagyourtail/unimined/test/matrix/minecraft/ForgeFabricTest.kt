package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests Forge + (Legacy) Fabric
 *
 * @see ForgeTest
 * @see FabricMinecraftTest
 * @see ForgeFabricLiteLoaderTest
 */
class ForgeFabricTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix(
			"1.7.10", "1.8.9", "1.14.4", "1.16.5", "1.17.1"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Forge-Fabric"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_forge_fabric(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
