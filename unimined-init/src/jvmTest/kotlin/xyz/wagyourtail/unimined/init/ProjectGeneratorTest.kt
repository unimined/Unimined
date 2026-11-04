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
        loaders: List<Pair<String, String>> = listOf("fabric" to "0.16.0"),
        templateName: String? = null,
        templatesZip: Path? = null
    ) = InitSpec(
        group = "com.example",
        name = "my-mod",
        mcVersion = "1.20.1",
        loaders = loaders,
        mappings = listOf("yarn(10)"),
        pluginVersion = "2.0.0-SNAPSHOT",
        templateName = templateName,
        templatesZip = templatesZip.toString()
    )

    @org.junit.jupiter.api.BeforeEach
    fun offlineWrapper() {
        // hermetic — bundled-resource fallback is the tested default
        ProjectGenerator.wrapperDownloader = { _, _ ->
            throw java.io.IOException("offline")
        }
    }

    @org.junit.jupiter.api.AfterEach
    fun restoreWrapper() {
        ProjectGenerator.wrapperDownloader = { _, _ ->
            throw java.io.IOException("offline")
        }
    }

    @Test
    fun `downloaded wrapper replaces bundled resources`(@TempDir dir: Path) {
        ProjectGenerator.wrapperDownloader = { _, out ->
            val od = Path.of(out)
            Files.writeString(od.resolve("gradlew"), "#!/bin/sh\n")
            Files.writeString(od.resolve("gradlew.bat"), "@rem bat\n")
            od.resolve("gradle/wrapper").let { d ->
                Files.createDirectories(d)
                Files.write(d.resolve("gradle-wrapper.jar"), byteArrayOf(1, 2))
            }
        }
        val written = ProjectGenerator.generate(spec(), dir.toString())
            .map { dir.relativize(java.nio.file.Path.of(it)).toString() }
        assertTrue("gradlew" in written && "gradlew.bat" in written)
        assertEquals("#!/bin/sh", Files.readAllLines(dir.resolve("gradlew")).first())
    }

    @Test
    fun `generates a complete fabric project`(@TempDir dir: Path) {
        val written = ProjectGenerator.generate(spec(), dir.toString()).map { dir.relativize(java.nio.file.Path.of(it)).toString() }
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
        // bundled .bat normalizes to CRLF — LF-only batch files break cmd
        assertTrue("\r\n" in Files.readString(dir.resolve("gradlew.bat")),
            "gradlew.bat not CRLF-normalized")
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
            spec(listOf("forge" to "47.2.0")), dir.toString()
        ).map { dir.relativize(java.nio.file.Path.of(it)).toString() }
        assertTrue("src/main/resources/META-INF/mods.toml" in written)
        val modsToml = Files.readString(dir.resolve("src/main/resources/META-INF/mods.toml"))
        assertTrue("my_mod" in modsToml)
        val main = Files.readString(dir.resolve("src/main/java/com/example/mymod/MyMod.java"))
        assertTrue("@Mod(\"my_mod\")" in main && "import net.minecraftforge.fml.common.Mod;" in main)
    }

    @Test
    fun `refuses a non-empty directory without overwrite`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("existing.txt"), "x")
        assertThrows(IllegalArgumentException::class.java) {
            ProjectGenerator.generate(spec(), dir.toString())
        }
        // overwrite=true passes
        ProjectGenerator.generate(spec(), dir.toString(), overwrite = true)
        assertTrue(Files.exists(dir.resolve("build.gradle.kts")))
    }

    @Test
    fun `multi-loader emits per-source-set layout`(@TempDir dir: Path) {
        ProjectGenerator.generate(
            spec(listOf("fabric" to "0.16.0", "forge" to "47.2.0")), dir.toString()
        )
        val build = Files.readString(dir.resolve("build.gradle.kts"))
        // shared version/mappings live on the main env (top-level
        // minecraft{}); loader envs combineWith it and use the
        // env-name loader inference
        assertTrue("sourceSet(\"fabric\")" in build)
        assertTrue("sourceSet(\"forge\")" in build)
        assertTrue("combineWith(\"main\")" in build)
        assertTrue("loader(\"0.16.0\")" in build)
        assertTrue("loader(\"47.2.0\")" in build)
        assertTrue(Files.exists(dir.resolve("src/fabric/resources/fabric.mod.json")))
        assertTrue(Files.exists(dir.resolve("src/forge/resources/META-INF/mods.toml")))
        assertTrue(Files.exists(dir.resolve("src/fabric/java/com/example/mymod/MyMod.java")))
        assertTrue(Files.exists(dir.resolve("src/forge/java/com/example/mymod/MyMod.java")))
    }

    @Test
    fun `template mode extracts testing subtree from zip`(
        @TempDir dir: Path, @TempDir zipDir: Path
    ) {
        // fake unimined-templates.zip: testing/demo/ + other/ entries
        val zipPath = zipDir.resolve("templates.zip")
        java.util.zip.ZipOutputStream(
            Files.newOutputStream(zipPath)
        ).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("testing/demo/build.gradle.kts"))
            z.write("// demo build\n".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("testing/demo/src/main/java/Foo.java"))
            z.write("class Foo {}\n".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("testing/other/settings.gradle.kts"))
            z.write("// not demo\n".toByteArray()); z.closeEntry()
        }
        val written = ProjectGenerator.generate(
            spec(templateName = "demo", templatesZip = zipPath), dir.toString()
        )
        assertTrue(Files.exists(dir.resolve("build.gradle.kts")))
        assertTrue(Files.exists(dir.resolve("src/main/java/Foo.java")))
        // other template subtrees stay out
        assertTrue(!Files.exists(dir.resolve("settings.gradle.kts")))
        assertEquals(2, written.size)
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


    @Test
    fun `multiloader emits common + per-loader modules`(@TempDir tmp: Path) {
        val spec = spec(listOf("fabric" to "0.16.0", "forge" to "47.2.0"))
            .copy(layout = InitSpec.Layout.MULTILOADER)
        val files = ProjectGenerator.files(spec)
        assertTrue("common/build.gradle.kts" in files)
        assertTrue("fabric/build.gradle.kts" in files)
        assertTrue("forge/build.gradle.kts" in files)
        assertTrue("build.gradle.kts" !in files)
        val settings = (files["settings.gradle.kts"] as ProjectGenerator.Render.Text).content
        assertTrue("\"common\"" in settings && "\"fabric\"" in settings &&
                "\"forge\"" in settings)
        val forgeBuild = (files["forge/build.gradle.kts"] as
                ProjectGenerator.Render.Text).content
        assertTrue("loader(\"forge\", \"47.2.0\")" in forgeBuild)
        assertTrue("project(\":common\")" in forgeBuild)
        assertTrue("fabric/src/main/java/com/example/mymod/MyMod.java" in files)
    }

    @Test
    fun `custom-product emits preset env block`(@TempDir tmp: Path) {
        val spec = spec().copy(
            layout = InitSpec.Layout.CUSTOM_PRODUCT,
            product = "bta",
        )
        val files = ProjectGenerator.files(spec)
        val build = (files["build.gradle.kts"] as ProjectGenerator.Render.Text)
            .content
        assertTrue("bta {" in build)
        assertTrue("version = \"1.20.1\"" in build)
        assertTrue("minecraft" !in build)
    }

    @Test
    fun `custom file-based product emits custom() with file`() {
        val spec = spec().copy(
            layout = InitSpec.Layout.CUSTOM_PRODUCT,
            product = "custom:prcraft",
            distFile = "dist/prcraft.jar",
        )
        val build = (ProjectGenerator.files(spec)["build.gradle.kts"] as
                ProjectGenerator.Render.Text).content
        assertTrue("custom(\"prcraft\")" in build)
        assertTrue("file(\"dist/prcraft.jar\")" in build)
    }

    @Test
    fun `jarmod emits client server jars and transform stubs`() {
        val spec = spec().copy(jarmod = true)
        val files = ProjectGenerator.files(spec)
        assertTrue("src/client/java/com/example/mymod/MyModClient.java" in files)
        assertTrue("src/server/java/com/example/mymod/MyModServer.java" in files)
        assertTrue("src/client/resources/my_mod-client.transform" in files)
        assertTrue("src/server/resources/my_mod-server.transform" in files)
        val build = (files["build.gradle.kts"] as
                ProjectGenerator.Render.Text).content
        assertTrue("clientJar" in build && "serverJar" in build)
        assertTrue("JarModAgent-Transforms" in build)
    }
}
