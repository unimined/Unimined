package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

class FlintTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix(
			"1.20.4"
		)
	}

	override val String.projectPath: String
		get() = "${this}-Flint"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_flint(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
