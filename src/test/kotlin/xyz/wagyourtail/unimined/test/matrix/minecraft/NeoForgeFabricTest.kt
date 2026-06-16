package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * @see ForgeTest
 * @see FabricMinecraftTest
 * @see ForgeFabricTest
 */
class NeoForgeFabricTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix(
			"1.21", "1.20.1", "1.20.2", "1.20.4", "1.20.6"
		)
	}

	override val String.projectPath: String
		get() = "$this}-NeoForge-Fabric"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_neoforge_fabric(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
