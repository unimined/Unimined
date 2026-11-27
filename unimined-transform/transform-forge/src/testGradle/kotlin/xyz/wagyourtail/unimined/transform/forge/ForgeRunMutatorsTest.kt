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

class SubstituteBrokenLibraryCoordTest {

    private fun substitute(coord: String) =
        xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
            .substituteBrokenLibraryCoord(coord)

    @Test
    fun `securejarhandler 3_0_8 pins to 3_0_9`() {
        // 3.0.8 was republished in place with JDK-25 bytecode while
        // its Gradle metadata still claims jvm-version=21 — the
        // substitution keeps dev launches on JDK-21-class bytecode
        assertEquals("cpw.mods:securejarhandler:3.0.9",
            substitute("cpw.mods:securejarhandler:3.0.8"))
        assertEquals("cpw.mods:securejarhandler:3.0.9@jar",
            substitute("cpw.mods:securejarhandler:3.0.8@jar"))
    }

    @Test
    fun `other coords pass through`() {
        assertEquals("cpw.mods:securejarhandler:3.0.9",
            substitute("cpw.mods:securejarhandler:3.0.9"))
        assertEquals("cpw.mods:securejarhandler:2.1.10",
            substitute("cpw.mods:securejarhandler:2.1.10"))
        assertEquals("net.minecraftforge:forge:1.21.1-52.1.0",
            substitute("net.minecraftforge:forge:1.21.1-52.1.0"))
        assertEquals("a:b", substitute("a:b"))
    }
}
