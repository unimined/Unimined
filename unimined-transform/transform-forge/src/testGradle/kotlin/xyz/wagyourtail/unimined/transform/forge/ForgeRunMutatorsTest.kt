package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeEnvironmentWiring
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.installer.detectTweakClass
import xyz.wagyourtail.unimined.transform.forge.internal.installer.bootstrapDevNeedsSemicolon
import java.nio.file.FileSystems
import java.nio.file.Files

class ForgeRunMutatorsTest {

    private fun jarWith(vararg entries: String) =
        Files.createTempDirectory("forge-").resolve("forge.jar").also { jar ->
            FileSystems.newFileSystem(jar, mapOf("create" to "true")).use { fs ->
                entries.forEach {
                    fs.getPath(it).also { p ->
                        p.parent?.let { Files.createDirectories(it) }
                        Files.write(p, byteArrayOf())
                    }
                }
            }
        }

    @Test
    fun `tweak class detects cpw package`() {
        val jar = jarWith("cpw/mods/fml/common/launcher/FMLTweaker.class")
        assertEquals(
            "cpw.mods.fml.common.launcher.FMLTweaker",
            detectTweakClass(jar, "client")
        )
        assertNull(detectTweakClass(jar, "server"))
    }

    @Test
    fun `tweak class detects forge package`() {
        val jar = jarWith(
            "net/minecraftforge/fml/common/launcher/FMLTweaker.class",
            "net/minecraftforge/fml/common/launcher/FMLServerTweaker.class"
        )
        assertEquals(
            "net.minecraftforge.fml.common.launcher.FMLTweaker",
            detectTweakClass(jar, "client")
        )
        assertEquals(
            "net.minecraftforge.fml.common.launcher.FMLServerTweaker",
            detectTweakClass(jar, "server")
        )
    }

    @Test
    fun `bootstrap-dev separator quirk`() {
        assertTrue(
            bootstrapDevNeedsSemicolon("1.0.0")
        )
        assertTrue(
            bootstrapDevNeedsSemicolon("2.1.3")
        )
        assertFalse(
            bootstrapDevNeedsSemicolon("2.1.4")
        )
        assertFalse(
            bootstrapDevNeedsSemicolon("2.2.0")
        )
    }
}

class DevPackageTest {

    private fun devJson(body: String) = ForgeProfile.Binpatches.DevPackage(
        java.nio.file.Paths.get("unused.jar"),
        kotlinx.serialization.json.Json.parseToJsonElement(
            "{$body}"
        ).jsonObject
    )

    @Test
    fun `devJson carries launchwrapper main and args`() {
        val pkg = devJson(
            """
            "mainClass": "net.minecraft.launchwrapper.Launch",
            "minecraftArguments": "--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker --assetsDir assets",
            "libraries": [{"name": "net.minecraft:launchwrapper:1.12"},
                          {"name": "org.scala-lang:scala-library:2.11.1"}]
        """
        )
        assertEquals("net.minecraft.launchwrapper.Launch", pkg.mainClass)
        assertEquals(
            listOf(
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker",
                "--assetsDir", "assets"
            ), pkg.programArgs
        )
        assertEquals(
            listOf(
                "net.minecraft:launchwrapper:1.12",
                "org.scala-lang:scala-library:2.11.1"
            ), pkg.libraries
        )
    }
}
