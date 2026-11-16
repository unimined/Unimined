package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readText

/**
 * Userdev installer extraction — `ats`/`inject` prefixes, `sources`
 * minus patches, `libraries` filtering, client-extra packaging.
 */
class UserdevExtractTest {

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
    fun `extractPrefix pulls a directory of AT files`() {
        val jar = jar(
            "ud.jar", mapOf(
                "ats/fml_at.cfg" to "public a b",
                "ats/forge_at.cfg" to "public-f c d",
                "other/x.txt" to "skip"
            )
        )
        val out = UserdevExtract.extractPrefix(jar, "ats/", dir.resolve("cache"))
        assertEquals(
            setOf("fml_at.cfg", "forge_at.cfg"),
            out.map { it.fileName.toString() }.toSet()
        )
        assertEquals(
            "public-f c d",
            dir.resolve("cache/ats/forge_at.cfg").readText()
        )
    }

    @Test
    fun `extractPrefix does not rewrite existing entries`() {
        val jar = jar(
            "ud.jar", mapOf(
                "ats/fml_at.cfg" to "public a b",
                "ats/forge_at.cfg" to "public-f c d"
            )
        )
        val cache = dir.resolve("cache")
        UserdevExtract.extractPrefix(jar, "ats/", cache)
        val target = cache.resolve("ats/forge_at.cfg")
        val old = java.nio.file.attribute.FileTime.fromMillis(1_000)
        java.nio.file.Files.setLastModifiedTime(target, old)

        // re-extraction is exists-gated — a rewrite would churn the dir
        // listing the configuration cache fingerprints as an input
        UserdevExtract.extractPrefix(jar, "ats/", cache)
        assertEquals(old, java.nio.file.Files.getLastModifiedTime(target))
        assertEquals("public-f c d", target.readText())
    }

    @Test
    fun `extractPrefix single file`() {
        val jar = jar(
            "ud.jar", mapOf(
                "META-INF/accesstransformer.cfg" to "public a"
            )
        )
        val out = UserdevExtract.extractPrefix(
            jar, "META-INF/accesstransformer.cfg", dir.resolve("cache")
        )
        assertEquals(1, out.size)
        assertTrue(out[0].isRegularFile())
    }

    @Test
    fun `extractSources skips patches`() {
        val jar = jar(
            "sources.jar", mapOf(
                "net/minecraftforge/common/ForgeHooks.java" to "// src",
                "patches/minecraft/net/minecraft/world/World.java.patch" to "patch",
                "patches/minecraft/net/minecraft/item/Item.java.patch" to "patch"
            )
        )
        val out = UserdevExtract.extractSources(jar, dir.resolve("src"))
        assertEquals(
            listOf("net/minecraftforge/common/ForgeHooks.java"),
            out.map { dir.resolve("src").relativize(it).toString() }
        )
    }

    @Test
    fun `injectEntries strips prefix`() {
        val jar = jar(
            "ud.jar", mapOf(
                "inject/net/minecraftforge/common/ForgeVersion.class" to "cls",
                "inject/net/minecraftforge/fml/Loader.class" to "cls2",
                "META-INF/MANIFEST.MF" to "x"
            )
        )
        val entries = UserdevExtract.injectEntries(jar, "inject/")
        assertEquals(
            mapOf(
                "net/minecraftforge/common/ForgeVersion.class" to "cls",
                "net/minecraftforge/fml/Loader.class" to "cls2"
            ).mapValues { it.value.toByteArray().toList() },
            entries.mapValues { it.value.toList() }
        )
    }

    /**
     * `install_profile.json` → `processors[].jar` — the installer
     * chain's declared tool table the split-side `data/<side>.lzma`
     * packs were built against (B6.9).
     */
    @Test
    fun `installerToolCoord reads the installer profile processors`() {
        val jar = jar(
            "installer.jar", mapOf(
                "install_profile.json" to """{"processors": [
                    {"jar": "net.minecraftforge:installertools:1.4.1:fatjar"},
                    {"jar": "net.minecraftforge:jarsplitter:1.1.4"},
                    {"jar": "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all"},
                    {"jar": "net.minecraftforge:binarypatcher:1.1.1"}
                ]}""",
                "data/server.lzma" to "x"
            )
        )
        assertEquals(
            listOf(
                "net.minecraftforge:installertools:1.4.1:fatjar",
                "net.minecraftforge:jarsplitter:1.1.4",
                "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all",
                "net.minecraftforge:binarypatcher:1.1.1"
            ),
            UserdevExtract.installerProcessorCoords(jar)
        )
        assertEquals(
            "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all",
            UserdevExtract.installerToolCoord(
                jar, "net.minecraftforge", "ForgeAutoRenamingTool")
        )
        assertNull(
            UserdevExtract.installerToolCoord(
                jar, "net.minecraftforge", "NotATool"))
    }

    @Test
    fun `installerToolCoord absent without a profile`() {
        val jar = jar("bare.jar", mapOf("x" to "y"))
        assertTrue(UserdevExtract.installerProcessorCoords(jar).isEmpty())
        assertNull(
            UserdevExtract.installerToolCoord(
                jar, "net.minecraftforge", "ForgeAutoRenamingTool"))
    }

    @Test
    fun `libraryCoords skips legacydev and pins gson`() {
        val cfg = UserdevConfig(
            Json.parseToJsonElement(
                """
            {"libraries": [
              {"name": "net.minecraftforge:legacydev:0.2.3.16:client"},
              {"name": "com.google.code.gson:gson:2.8.0"},
              {"name": "net.jodah:typetools:0.5.0"}
            ]}
        """
            ).let { it as kotlinx.serialization.json.JsonObject })
        assertEquals(
            listOf(
                "com.google.code.gson:gson:2.8.9",
                "net.jodah:typetools:0.5.0"
            ),
            UserdevExtract.libraryCoords(cfg)
        )
    }

    @Test
    fun `writeClientExtra keeps non-class assets`() {
        val client = jar(
            "client.jar", mapOf(
                "net/minecraft/client/Minecraft.class" to "cls",
                "assets/minecraft/textures/block/dirt.png" to "png",
                "META-INF/MANIFEST.MF" to "mf",
                "data/minecraft/loot_tables/x.json" to "{}"
            )
        )
        val out = dir.resolve("client-extra.jar")
        UserdevExtract.writeClientExtra(client, out)
        assertTrue(out.exists())
        JarFile(out.toFile()).use { j ->
            val names = j.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf(
                    "assets/minecraft/textures/block/dirt.png",
                    "data/minecraft/loot_tables/x.json",
                    "META-INF/MANIFEST.MF"
                ),
                names
            )
            // the union FS serves this manifest (getMinecraftOnly's first
            // member after reversal). It must exist — to shadow the merged
            // jar's `net.minecraftforge.forge` AMN — but carry no AMN, so
            // the MinecraftLocator modfile falls back to synthetic modId
            // `minecraft` and the raw union path stays unnamed.
            assertEquals(null, j.manifest.mainAttributes.getValue("Automatic-Module-Name"))
        }
    }
}
