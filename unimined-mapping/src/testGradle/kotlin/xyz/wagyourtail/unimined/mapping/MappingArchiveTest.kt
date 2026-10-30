package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.internal.MappingArchive
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class MappingArchiveTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(name: String, vararg entries: Pair<String, String>): Path {
        val file = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(file)).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(JarEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `prefers mappings-mappings-tiny inside a jar`() {
        val archive = jar(
            "yarn.jar",
            "mappings/mappings.tiny" to "tiny\t2\t0\ta\tb\n",
            "other.txt" to "noise"
        )
        val extracted = MappingArchive.extract(archive, dir.resolve("out"))!!
        assertEquals(1, extracted.size)
        assertEquals(
            "tiny\t2\t0\ta\tb\n",
            extracted.single().toFile().readText()
        )
    }

    @Test
    fun `extracts a single recognized mapping entry when the preferred path is absent`() {
        val archive = jar(
            "src.jar",
            "META-INF/" to "",
            "doc.tsrg" to "a b\n",
            "readme.md" to "text"
        )
        val extracted = MappingArchive.extract(archive, dir.resolve("out"))!!
        assertEquals(1, extracted.size)
        assertTrue(extracted.single().fileName.toString().endsWith("doc.tsrg"))
    }

    @Test
    fun `multiple candidate mapping entries extract deterministically`() {
        val archive = jar(
            "ambiguous.jar",
            "a.tiny" to "tiny\t2\t0\ta\tb\n",
            "b.tiny" to "tiny\t2\t0\ta\tb\n"
        )
        val extracted = MappingArchive.extract(archive, dir.resolve("out"))
        assertEquals(listOf("a.tiny", "b.tiny"),
            extracted!!.map { it.fileName.toString() })
    }

    @Test
    fun `non-archive files and archives without mappings return null`() {
        val plain = dir.resolve("plain.tiny").also {
            Files.writeString(it, "tiny\t2\t0\ta\tb\n")
        }
        assertNull(MappingArchive.extract(plain, dir.resolve("out")))

        val empty = jar("empty.jar", "readme.md" to "text")
        assertNull(MappingArchive.extract(empty, dir.resolve("out")))
    }

}
