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

}
