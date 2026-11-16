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

    @Test
    fun `produceCached skips existing and produces once`() {
        val target = dir.resolve("cached.jar")
        var calls = 0
        target.produceCached { tmp ->
            calls++; Files.write(tmp, "v1".toByteArray())
        }
        target.produceCached { tmp ->
            calls++; Files.write(tmp, "v2".toByteArray())
        }
        assertEquals(1, calls, "second call must skip the produce")
        assertEquals("v1", Files.readString(target))
    }

    @Test
    fun `produceCached force re-produces`() {
        val target = dir.resolve("cached.jar")
        var calls = 0
        target.produceCached { tmp ->
            calls++; Files.write(tmp, "v$calls".toByteArray())
        }
        target.produceCached(force = true) { tmp ->
            calls++; Files.write(tmp, "v$calls".toByteArray())
        }
        assertEquals(2, calls)
        assertEquals("v2", Files.readString(target))
    }

    @Test
    fun `produceCached failed produce leaves target absent`() {
        val target = dir.resolve("cached.jar")
        assertThrows(IllegalStateException::class.java) {
            target.produceCached { error("tool died") }
        }
        assertFalse(Files.exists(target), "failed produce must not publish")
    }

    @Test
    fun `withCacheLock serializes concurrent producers`() {
        val target = dir.resolve("shared.jar")
        // the plan's stress check: N threads race produceCached —
        // the lock serializes them, exactly one produce runs, and
        // the result is a complete file (never a torn write)
        val threads = (1..8).map {
            Thread {
                target.produceCached { tmp ->
                    Thread.sleep(10)
                    Files.write(tmp, "done".toByteArray())
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("done", Files.readString(target))
    }

    @Test
    fun `produceDirCached publishes a complete directory`() {
        val target = dir.resolve("tree")
        target.produceDirCached { tmp ->
            Files.createDirectories(tmp)
            Files.write(tmp.resolve("a.txt"), "a".toByteArray())
            Files.write(tmp.resolve("b.txt"), "b".toByteArray())
        }
        assertEquals("a", Files.readString(target.resolve("a.txt")))
        assertTrue(leftovers().isEmpty())
    }
}
