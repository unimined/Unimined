package xyz.wagyourtail.unimined.init

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** Acceptance test per plan §13: a generated project is complete. */
class ProjectGeneratorTest {

    private fun spec(
        loaders: List<Pair<String, String>> = listOf("fabric" to "0.16.0")
    ) = InitSpec(
        group = "com.example",
        name = "my-mod",
        mcVersion = "1.20.1",
        loaders = loaders,
        mappings = listOf("yarn(10)"),
        pluginVersion = "2.0.0-SNAPSHOT"
    )

    @Test
    fun `generates a complete fabric project`(@TempDir dir: Path) {
        val written = ProjectGenerator.generate(spec(), dir).map { dir.relativize(it).toString() }
        for (required in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
            ".gitignore", "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "src/main/java/com/example/mymod/MyMod.java",
            "src/main/resources/fabric.mod.json"
        )) {
            assertTrue(required in written, "missing $required")
        }
        assertTrue(dir.resolve("gradlew").toFile().canExecute(), "gradlew executable")
        val build = Files.readString(dir.resolve("build.gradle.kts"))
        assertTrue("loader(\"fabric\", \"0.16.0\")" in build)
        assertTrue("yarn(10)" in build)
        assertTrue("xyz.wagyourtail.unimined" in build)
        val fm = Files.readString(dir.resolve("src/main/resources/fabric.mod.json"))
        assertTrue("\"my_mod\"" in fm)
        assertTrue("com.example.mymod.MyMod" in fm)
        val wrapperJar = dir.resolve("gradle/wrapper/gradle-wrapper.jar")
        assertTrue(Files.size(wrapperJar) > 10_000, "wrapper jar is real")
    }

    @Test
    fun `generates a forge project with mods toml`(@TempDir dir: Path) {
        val written = ProjectGenerator.generate(
            spec(listOf("forge" to "47.2.0")), dir
        ).map { dir.relativize(it).toString() }
        assertTrue("src/main/resources/META-INF/mods.toml" in written)
        val modsToml = Files.readString(dir.resolve("src/main/resources/META-INF/mods.toml"))
        assertTrue("my_mod" in modsToml)
        val main = Files.readString(dir.resolve("src/main/java/com/example/mymod/MyMod.java"))
        assertTrue("@net.minecraftforge.fml.common.Mod(\"my_mod\")" in main)
    }

    @Test
    fun `refuses a non-empty directory without overwrite`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("existing.txt"), "x")
        assertThrows(IllegalArgumentException::class.java) {
            ProjectGenerator.generate(spec(), dir)
        }
        // overwrite=true passes
        ProjectGenerator.generate(spec(), dir, overwrite = true)
        assertTrue(Files.exists(dir.resolve("build.gradle.kts")))
    }

    @Test
    fun `multi-loader emits per-source-set layout`(@TempDir dir: Path) {
        ProjectGenerator.generate(
            spec(listOf("fabric" to "0.16.0", "forge" to "47.2.0")), dir
        )
        val build = Files.readString(dir.resolve("build.gradle.kts"))
        assertTrue("sourceSet(\"fabric\")" in build)
        assertTrue("sourceSet(\"forge\")" in build)
        assertTrue("loader(\"fabric\", \"0.16.0\")" in build)
        assertTrue("loader(\"forge\", \"47.2.0\")" in build)
        assertTrue(Files.exists(dir.resolve("src/fabric/resources/fabric.mod.json")))
        assertTrue(Files.exists(dir.resolve("src/forge/resources/META-INF/mods.toml")))
        assertTrue(Files.exists(dir.resolve("src/fabric/java/com/example/mymod/MyMod.java")))
        assertTrue(Files.exists(dir.resolve("src/forge/java/com/example/mymod/MyMod.java")))
    }

    @Test
    fun `modid and package derive correctly`() {
        val s = InitSpec(
            group = "com.Example-Corp", name = "My Cool Mod",
            mcVersion = "1.20.1", loaders = emptyList(),
            pluginVersion = "x"
        )
        assertEquals("my_cool_mod", s.modId)
        assertEquals("com.examplecorp.mycoolmod", s.basePackage)
        assertEquals("MyCoolMod", Templates.mainClassName(s))
    }

}
