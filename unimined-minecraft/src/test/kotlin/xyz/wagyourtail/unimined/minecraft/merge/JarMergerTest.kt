package xyz.wagyourtail.unimined.minecraft.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class JarMergerTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(path: Path, entries: Map<String, String>) {
        ZipOutputStream(path.outputStream()).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(java.util.zip.ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun entryNames(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun `merge combines entries, client wins on conflict`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, mapOf("a.txt" to "client-a", "shared.txt" to "client"))
        jar(server, mapOf("b.txt" to "server-b", "shared.txt" to "server"))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out)

        ZipFile(out.toFile()).use { z ->
            assertEquals("client-a", z.getInputStream(z.getEntry("a.txt")).readBytes().decodeToString())
            assertEquals("server-b", z.getInputStream(z.getEntry("b.txt")).readBytes().decodeToString())
            assertEquals("client", z.getInputStream(z.getEntry("shared.txt")).readBytes().decodeToString())
        }
    }

    @Test
    fun `server-bundled library entries are stripped, client kept verbatim`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(
            client, mapOf(
                "net/minecraft/Foo.class" to "mc-client",
                // client may legitimately carry a same-path class — a
                // whitelist would drop it; membership strip only reads the
                // server side
                "com/mojang/renderpearl/Thing.class" to "renderpearl"
            )
        )
        jar(
            server, mapOf(
                "net/minecraft/Bar.class" to "mc-server",
                "com/google/common/Foo.class" to "bundled-guava",
                "it/unimi/dsi/fastutil/IntList.class" to "bundled-fastutil",
                "org/example/custom/ServerOnly.class" to "server-only"
            )
        )
        jar(
            lib, mapOf(
                "com/google/common/Foo.class" to "guava",
                "it/unimi/dsi/fastutil/IntList.class" to "fastutil"
            )
        )

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out, JarMerger.Options(libraryJars = listOf(lib)))

        assertEquals(
            setOf(
                "net/", "net/minecraft/", "com/", "com/mojang/",
                "com/mojang/renderpearl/", "org/", "org/example/",
                "org/example/custom/",
                "net/minecraft/Foo.class",
                "com/mojang/renderpearl/Thing.class",
                "net/minecraft/Bar.class",
                "org/example/custom/ServerOnly.class"
            ), entryNames(out)
        )
    }

    @Test
    fun `exclude and include globs override membership strip`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(client, mapOf("a.class" to "a"))
        jar(
            server, mapOf(
                "b.class" to "b",
                "lib/Lib.class" to "lib-copy",
                "org/mozilla/rhino/Script.class" to "undeclared-bundled",
                "lib/Keep.class" to "keep-me"
            )
        )
        jar(
            lib, mapOf(
                "lib/Lib.class" to "lib",
                "lib/Keep.class" to "lib"
            )
        )

        val out = dir.resolve("merged.jar")
        JarMerger.merge(
            client, server, out, JarMerger.Options(
                libraryJars = listOf(lib),
                includeGlobs = listOf("lib/Keep.class"),
                excludeGlobs = listOf("org/mozilla/**")
            )
        )

        assertEquals(setOf("lib/", "a.class", "b.class", "lib/Keep.class"), entryNames(out))
    }

    @Test
    fun `strip disabled keeps bundled library entries`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(client, mapOf("a.class" to "a"))
        jar(server, mapOf("lib/Lib.class" to "bundled"))
        jar(lib, mapOf("lib/Lib.class" to "lib"))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(
            client, server, out, JarMerger.Options(
                libraryJars = listOf(lib),
                stripBundledServerLibraries = false
            )
        )

        assertEquals(setOf("lib/", "a.class", "lib/Lib.class"), entryNames(out))
    }

    @Test
    fun `standalone strip drops bundled members, keeps manifest and services`() {
        val input = dir.resolve("input.jar")
        val lib = dir.resolve("lib.jar")
        jar(lib, mapOf(
            "META-INF/MANIFEST.MF" to "lib-manifest",
            "META-INF/services/spi.Provider" to "lib.Provider",
            "org/slf4j/LoggerFactory.class" to "lib",
            "org/slf4j/spi/Only.class" to "lib",
        ))
        jar(input, mapOf(
            "META-INF/MANIFEST.MF" to "dist-manifest",
            "META-INF/services/spi.Provider" to "dist.Provider",
            "game/Main.class" to "game",
            "org/slf4j/LoggerFactory.class" to "bundled",
            "org/slf4j/spi/Only.class" to "bundled",
        ))

        val out = dir.resolve("stripped.jar")
        BundledLibraryStrip.strip(input, listOf(lib), out)

        val entries = entryNames(out)
        // bundled lib classes dropped — emptied package dirs are gone too
        assertEquals(
            setOf("META-INF/", "META-INF/MANIFEST.MF",
                "META-INF/services/", "META-INF/services/spi.Provider",
                "game/", "game/Main.class"),
            entries
        )
        // input's own manifest + service file survive — membership
        // can't strip non-library-code entries
        java.util.zip.ZipFile(out.toFile()).use {
            assertEquals("dist-manifest",
                it.getInputStream(
                    it.getEntry("META-INF/MANIFEST.MF")).reader().readText())
            assertEquals("dist.Provider",
                it.getInputStream(
                    it.getEntry("META-INF/services/spi.Provider")).reader().readText())
        }
    }

}
