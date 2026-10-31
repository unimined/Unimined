package xyz.wagyourtail.unimined.provider.buildtools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** BuildToolsExec: per-version workdirs, locking, output discovery. */
class BuildToolsExecTest {

    @TempDir
    lateinit var dir: Path

    /** Exec whose javaExec fabricates the expected output jar. */
    private fun exec(
        producedJar: String = "spigot-1.20.4.jar",
        invocations: ConcurrentLinkedQueue<Path>? = null
    ): BuildToolsExec {
        val downloader = Downloader(
            cacheDir = dir.resolve("cache"), offline = false,
            refreshCache = false,
            metadataTtl = java.time.Duration.ofHours(24)
        ) { _, _ -> }
        return BuildToolsExec(downloader, dir.resolve("work")) { _, _, d ->
            invocations?.add(d)
            Files.write(d.resolve(producedJar), "jar".toByteArray())
        }
    }

    @Test
    fun `serverJar produces and locates the version-scoped output`() {
        val out = exec().serverJar("spigot", "1.20.4")
        assertTrue(out.fileName.toString().startsWith("spigot-"))
        assertTrue(out.parent.fileName.toString() == "spigot-1.20.4")
    }

    @Test
    fun `same version resolves idempotently without re-running`() {
        val runs = ConcurrentLinkedQueue<Path>()
        val e = exec(invocations = runs)
        e.serverJar("spigot", "1.20.4")
        e.serverJar("spigot", "1.20.4")
        assertEquals(1, runs.size)
    }

    @Test
    fun `different versions get separate workdirs`() {
        val runs = ConcurrentLinkedQueue<Path>()
        val e = exec(invocations = runs)
        e.serverJar("spigot", "1.20.4")
        e.serverJar("spigot", "1.19.4")
        assertEquals(2, runs.size)
        assertTrue(runs.any { it.fileName.toString() == "spigot-1.19.4" })
    }

    @Test
    fun `concurrent same-version runs serialize to one build`() {
        val runs = ConcurrentLinkedQueue<Path>()
        val e = exec(invocations = runs)
        (1..4).map {
            thread { e.serverJar("spigot", "1.20.4") }
        }.forEach { it.join() }
        assertEquals(1, runs.size)
    }
}
