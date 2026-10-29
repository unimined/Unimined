package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ForgeInstallerProbe
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Era classification by artifact shape — userdev `config.json`, FG2
 * `dev.json` dev-package, FG2 universal `binpatches.pack.lzma`, FG1
 * baked patch classes.
 */
class ForgeInstallerProbeTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(name: String, entries: Map<String, String>): Path {
        val jar = dir.resolve(name)
        JarOutputStream(jar.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(JarEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `config-json jar classifies as userdev`() {
        val jar = jar(
            "forge-userdev.jar", mapOf(
                "config.json" to """{"mcp": "de.oceanlabs.mcp:mcp_config:1.16.5@zip"}""",
                "binpatches.pack.lzma" to "x"
            )
        )
        val profile = ForgeInstallerProbe.inspect(jar)
        assertTrue(profile is ForgeProfile.Userdev)
        assertEquals(
            "de.oceanlabs.mcp:mcp_config:1.16.5@zip",
            (profile as ForgeProfile.Userdev).config.mcp
        )
        // searge default: mcp_config + no notchObf
        assertEquals("searge", profile.config.obfNamespace)
    }

    @Test
    fun `userdev config interprets neoform and notchObf`() {
        val neoform = jar(
            "nf.jar", mapOf(
                "config.json" to """{"mcp": "net.neoforged:neoform:1.21@zip"}"""
            )
        )
        assertEquals(
            "mojmap",
            (ForgeInstallerProbe.inspect(neoform) as ForgeProfile.Userdev).config.obfNamespace
        )
        val notch = jar(
            "notch.jar", mapOf(
                "config.json" to """{"notchObf": true, "mcp": "de.oceanlabs.mcp:mcp_config:1.12.2@zip"}"""
            )
        )
        val cfg = (ForgeInstallerProbe.inspect(notch) as ForgeProfile.Userdev).config
        assertEquals("official", cfg.obfNamespace)
        assertTrue(cfg.notchObf)
    }

    @Test
    fun `userdev config exposes ats binpatches and libraries`() {
        val jar = jar(
            "ud.jar", mapOf(
                "config.json" to """
                {
                  "ats": "META-INF/accesstransformer.cfg",
                  "binpatches": "binpatches.pack.lzma",
                  "binpatcher": "net.minecraftforge:binarypatcher:1.1.1:fatjar",
                  "patches": "patches/",
                  "inject": "net/minecraftforge/",
                  "libraries": [
                    {"name": "net.minecraftforge:legacydev:0.2.3.16:client"},
                    "com.google.code.gson:gson:2.8.9"
                  ],
                  "runs": {"client": {"main": "mcp.client.Start"}}
                }
            """.trimIndent()
            )
        )
        val cfg = (ForgeInstallerProbe.inspect(jar) as ForgeProfile.Userdev).config
        assertEquals("META-INF/accesstransformer.cfg", cfg.ats)
        assertEquals("binpatches.pack.lzma", cfg.binpatches)
        assertEquals(
            listOf(
                "net.minecraftforge:legacydev:0.2.3.16:client",
                "com.google.code.gson:gson:2.8.9"
            ),
            cfg.libraries
        )
        assertEquals(setOf("client"), cfg.runs.keys)
        assertEquals("net/minecraftforge/", cfg.inject)
    }

    @Test
    fun `dev-json jar classifies as binpatches dev package`() {
        val jar = jar(
            "fg2-userdev.jar", mapOf(
                "dev.json" to """{"inheritsFrom": "1.12.2", "minecraftArguments": ""}""",
                "devbinpatches.pack.lzma" to "x",
                "merged_at.cfg" to "public a b",
                "merged.srg" to "x",
                "sources.zip" to "x"
            )
        )
        val profile = ForgeInstallerProbe.inspect(jar)
        assertTrue(profile is ForgeProfile.Binpatches)
        val dev = (profile as ForgeProfile.Binpatches).devPackage
        assertTrue(dev != null)
        assertEquals(
            "1.12.2",
            dev!!.devJson["inheritsFrom"]!!.toString().trim('"')
        )
        assertTrue(ForgeInstallerProbe.isDevPackage(jar))
    }

    @Test
    fun `universal binpatches jar classifies with null devPackage`() {
        val jar = jar(
            "forge-universal.jar", mapOf(
                "binpatches.pack.lzma" to "x",
                "version.json" to """{"id": "1.12.2-forge"}""",
                "forge_at.cfg" to "x"
            )
        )
        val profile = ForgeInstallerProbe.inspect(jar)
        assertTrue(profile is ForgeProfile.Binpatches)
        assertNull((profile as ForgeProfile.Binpatches).devPackage)
    }

    @Test
    fun `jar-diff patches classify as zipPatches`() {
        val jar = jar(
            "forge-zip.jar", mapOf(
                "net/minecraft/client/Minecraft.class" to "x",
                "net/minecraft/server/MinecraftServer.class" to "y"
            )
        )
        assertEquals(ForgeProfile.ZipPatches, ForgeInstallerProbe.inspect(jar))
    }

    @Test
    fun `unrecognized jar fails`() {
        val jar = jar("unknown.jar", mapOf("META-INF/MANIFEST.MF" to "x"))
        assertThrows<IllegalStateException> { ForgeInstallerProbe.inspect(jar) }
    }
}
