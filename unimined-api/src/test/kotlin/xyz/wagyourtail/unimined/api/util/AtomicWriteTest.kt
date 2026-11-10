package xyz.wagyourtail.unimined.api.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A killed/interrupted producer must never leave a partial artifact
 *  at the final path — existence-gated caches read "file present" as
 *  "artifact complete". */
class AtomicWriteTest {

    @TempDir
    lateinit var dir: Path

    private fun leftovers(): List<Path> =
        Files.list(dir).use { s ->
            s.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }

    @Test
    fun `success writes content and leaves no temp`() {
        val target = dir.resolve("out.jar")
        target.writeAtomically { tmp -> Files.write(tmp, "content".toByteArray()) }
        assertEquals("content", Files.readString(target))
        assertTrue(leftovers().isEmpty(), "temp files must be cleaned up")
    }

    @Test
    fun `failed produce leaves no file and cleans temp`() {
        val target = dir.resolve("out.jar")
        assertThrows(IllegalStateException::class.java) {
            target.writeAtomically { tmp ->
                Files.write(tmp, "partial".toByteArray())
                error("killed mid-write")
            }
        }
        assertFalse(Files.exists(target), "partial output must not appear")
        assertTrue(leftovers().isEmpty(), "temp files must be cleaned up")
    }

    @Test
    fun `failed produce preserves existing file`() {
        val target = dir.resolve("out.jar")
        Files.write(target, "original".toByteArray())
        assertThrows(IllegalStateException::class.java) {
            target.writeAtomically { tmp ->
                Files.write(tmp, "partial".toByteArray())
                error("killed mid-write")
            }
        }
        assertEquals("original", Files.readString(target))
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `produces over existing file atomically`() {
        val target = dir.resolve("out.jar")
        Files.write(target, "old".toByteArray())
        target.writeAtomically { tmp -> Files.write(tmp, "new".toByteArray()) }
        assertEquals("new", Files.readString(target))
        assertTrue(leftovers().isEmpty())
    }
}
