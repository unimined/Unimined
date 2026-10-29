package xyz.wagyourtail.unimined.provider.native.merge

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
    fun `merge combines entries, server wins on conflict`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, mapOf("a.txt" to "client-a", "shared.txt" to "client"))
        jar(server, mapOf("b.txt" to "server-b", "shared.txt" to "server"))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out)

        ZipFile(out.toFile()).use { z ->
            assertEquals("client-a", z.getInputStream(z.getEntry("a.txt")).readBytes().decodeToString())
            assertEquals("server-b", z.getInputStream(z.getEntry("b.txt")).readBytes().decodeToString())
            assertEquals("server", z.getInputStream(z.getEntry("shared.txt")).readBytes().decodeToString())
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

        assertEquals(setOf("a.class", "b.class", "lib/Keep.class"), entryNames(out))
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

        assertEquals(setOf("a.class", "lib/Lib.class"), entryNames(out))
    }

}
