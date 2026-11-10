package xyz.wagyourtail.unimined.provider.buildtools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** BuildToolsExec: per-version workdirs, locking, output discovery. */
class BuildToolsExecTest {

    @TempDir
    lateinit var dir: Path

    /** Exec whose javaExec fabricates the expected maven-module
     * output — real BuildTools writes `<module>/target/<target>-*.jar`
     * and copies a bootstrap launcher jar into the work-dir root. */
    private fun exec(
        producedJar: String = "spigot-1.20.4-R0.1-SNAPSHOT.jar",
        bootstrapJar: String? = "spigot-1.20.4.jar",
        invocations: ConcurrentLinkedQueue<List<String>>? = null
    ): BuildToolsExec {
        val downloader = Downloader(
            cacheDir = dir.resolve("cache"), offline = false,
            refreshCache = false,
            metadataTtl = java.time.Duration.ofHours(24)
        ) { _, _ -> }
        return BuildToolsExec(downloader, dir.resolve("work")) { _, args, d ->
            invocations?.add(args)
            Files.createDirectories(d.resolve("Mod/target"))
            Files.write(d.resolve("Mod/target/$producedJar"), "jar".toByteArray())
            if (bootstrapJar != null) {
                Files.write(d.resolve(bootstrapJar), "boot".toByteArray())
            }
        }
    }

    @Test
    fun `serverJar produces and locates the module output`() {
        val out = exec().serverJar("spigot", "1.20.4")
        assertTrue(out.fileName.toString().startsWith("spigot-"))
        assertTrue(out.parent.fileName.toString() == "target")
    }

    @Test
    fun `serverJar returns the module jar, not the root bootstrap`() {
        // the bootstrap jar carries only the launcher — the impl jar
        // in */target/ holds the server classes the dev view needs
        val out = exec().serverJar("spigot", "1.20.4")
        assertEquals("spigot-1.20.4-R0.1-SNAPSHOT.jar",
            out.fileName.toString())
    }

    @Test
    fun `serverJar skips classifier jars in the module output`() {
        val e = exec()
        val out = e.serverJar("spigot", "1.20.4")
        val target = out.parent
        Files.write(target.resolve("spigot-1.20.4-sources.jar"),
            "x".toByteArray())
        Files.write(target.resolve("original-spigot-1.20.4.jar"),
            "x".toByteArray())
        assertEquals(out, e.serverJar("spigot", "1.20.4"))
    }

    @Test
    fun `compile flag uses the BuildTools enum name`() {
        val runs = ConcurrentLinkedQueue<List<String>>()
        exec(producedJar = "craftbukkit-1.21.1-R0.1-SNAPSHOT.jar",
            bootstrapJar = "craftbukkit-1.21.1.jar",
            invocations = runs).serverJar("craftbukkit", "1.21.1")
        // the enum is [NONE, CRAFTBUKKIT, SPIGOT] — no hyphen
        assertTrue(runs.single().contains("CRAFTBUKKIT"))
    }

    @Test
    fun `embeddedLibraries extracts starred rows and skips absent entries`() {
        val work = dir.resolve("work/spigot-1.20.4")
        Files.createDirectories(work)
        ZipOutputStream(Files.newOutputStream(
            work.resolve("spigot-1.20.4.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/libraries.list"))
            zip.write(("hash1 *bukkit-1.20.4-R0.1-SNAPSHOT.jar\n" +
                "hash2 *guava-32.1.2-jre.jar\n" +
                // listed but not embedded — the mojang server jar
                // lives under versions/, not a classpath member
                "hash3 *minecraft-server-1.20.4-R0.1-SNAPSHOT.jar\n")
                .toByteArray())
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("META-INF/libraries/bukkit-1.20.4-R0.1-SNAPSHOT.jar"))
            zip.write("api".toByteArray()); zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("META-INF/libraries/guava-32.1.2-jre.jar"))
            zip.write("guava".toByteArray()); zip.closeEntry()
        }
        val e = BuildToolsExec(
            Downloader(cacheDir = dir.resolve("cache"), offline = false,
                refreshCache = false,
                metadataTtl = java.time.Duration.ofHours(24)) { _, _ -> },
            dir.resolve("work")) { _, _, _ -> }
        val libs = e.embeddedLibraries("spigot", "1.20.4",
            dir.resolve("out"))
        assertEquals(setOf("bukkit-1.20.4-R0.1-SNAPSHOT.jar",
            "guava-32.1.2-jre.jar"),
            libs.map { it.fileName.toString() }.toSet())
    }

    @Test
    fun `same version resolves idempotently without re-running`() {
        val runs = ConcurrentLinkedQueue<List<String>>()
        val e = exec(invocations = runs)
        e.serverJar("spigot", "1.20.4")
        e.serverJar("spigot", "1.20.4")
        assertEquals(1, runs.size)
    }

    @Test
    fun `different versions get separate workdirs`() {
        val runs = ConcurrentLinkedQueue<List<String>>()
        val e = exec(invocations = runs)
        e.serverJar("spigot", "1.20.4")
        e.serverJar("spigot", "1.19.4")
        assertEquals(2, runs.size)
    }

    @Test
    fun `concurrent same-version runs serialize to one build`() {
        val runs = ConcurrentLinkedQueue<List<String>>()
        val e = exec(invocations = runs)
        (1..4).map {
            thread { e.serverJar("spigot", "1.20.4") }
        }.forEach { it.join() }
        assertEquals(1, runs.size)
    }
}
