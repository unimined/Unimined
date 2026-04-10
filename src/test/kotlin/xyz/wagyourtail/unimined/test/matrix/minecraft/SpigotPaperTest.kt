package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests functions related to supporting Bukkit-like server fork patchers.
 */
class SpigotPaperTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		private val versions = versionMatrix(
			"1.16.5"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Spigot-Paper"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_spigot_paper(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
