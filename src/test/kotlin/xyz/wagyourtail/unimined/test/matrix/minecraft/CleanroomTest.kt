package xyz.wagyourtail.unimined.test.matrix.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import xyz.wagyourtail.unimined.test.matrix.VersionMatrixTest
import xyz.wagyourtail.unimined.util.IntegrationTestUtils.versionMatrix

class CleanroomTest : VersionMatrixTest {
	companion object {
		@JvmStatic
		val versions = versionMatrix("1.12.2")
	}

	override val String.projectPath: String
		get() = "${this}-Cleanroom"

	@ParameterizedTest(name = "[Gradle {0}] [Minecraft {1}]")
	@FieldSource("versions")
	fun test_cleanroom(gradleVersion: String, minecraftVersion: String) =
		super.testDirectory(gradleVersion, minecraftVersion)
}
