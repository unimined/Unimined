package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests functions related to Minecraft Beta 1.7.3,
 * such as the Babric patcher,
 * Babric intermediary provider, biny mappings provider,
 * RetroMCP mappings provider,
 * adding ModLoader, ModLoaderMP, and Forge API as jar mods
 */
class BabricModLoaderTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		private val versions = versionMatrix(
			"b1.7.3",
		)
	}

	override val String.projectPath: String
		get() = "${this}-Babric-ModLoader"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_babric_modloader(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
