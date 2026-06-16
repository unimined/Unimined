package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

/**
 * Tests functions related to supporting Minecraft Forge,
 * such as ForgeGradle patchers,
 * Searge intermediary provider, MCP mappings provider
 */
class ForgeTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		private val versions = versionMatrix(
			"1.3.2", "1.6.4"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Forge"

	@ParameterizedTest(name = "[Gradle {0}] [Forge] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_forge(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
