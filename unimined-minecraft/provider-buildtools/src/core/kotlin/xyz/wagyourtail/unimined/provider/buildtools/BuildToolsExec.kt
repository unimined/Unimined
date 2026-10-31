package xyz.wagyourtail.unimined.provider.buildtools

import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * Thin executor over Spigot's upstream `BuildTools.jar` — downloads
 * it once into the unimined cache, then runs
 * `java -jar BuildTools.jar --rev <rev> --compile <target>` inside a
 * per-`target-rev` work directory and locates the produced
 * `craftbukkit-<rev>.jar` / `spigot-<rev>.jar`.
 *
 * Concurrency: BuildTools clones repos and runs maven in-place, so
 * each `target-rev` gets its own work dir (parallel builds of
 * different versions can't collide), and same-key builds serialize
 * on a JVM lock + `.lock` file lock — covering both parallel
 * environments in one build and concurrent Gradle daemons sharing
 * the project cache.
 */
internal class BuildToolsExec(
    private val downloader: Downloader,
    private val workDir: Path,
    private val javaExec: (executable: String, args: List<String>, dir: Path) -> Unit
) {

    private fun buildTools(): Path = downloader.fetch(
        "https://hub.spigotmc.org/jenkins/job/BuildTools/" +
            "lastSuccessfulBuild/artifact/target/BuildTools.jar",
        "buildtools/BuildTools.jar"
    )

    /**
     * Produce the [target] server jar at [rev] (a Minecraft version
     * like `1.20.4`). Idempotent — skips when the output already
     * exists in the work dir.
     */
    fun serverJar(
        target: String, rev: String,
        javaExecutable: () -> String = { "java" }
    ): Path {
        val dir = workDir.resolve("$target-$rev")
        existingOutput(dir, target)?.let { return it }
        dir.createDirectories()
        val jvmLock = LOCKS.computeIfAbsent(dir) { ReentrantLock() }
        jvmLock.withLock {
            dir.resolve(".build.lock").let { lockPath ->
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE
                ).use { channel ->
                    channel.lock().use { _ ->
                        // re-check inside the lock — the run that held
                        // it may have produced the jar already
                        existingOutput(dir, target)?.let { return it }
                        javaExec(
                            javaExecutable(),
                            listOf(
                                "-jar", buildTools().toAbsolutePath().toString(),
                                "--rev", rev,
                                "--compile",
                                if (target == "spigot") "SPIGOT" else "CRAFT-BUKKIT"
                            ),
                            dir
                        )
                    }
                }
            }
        }
        return existingOutput(dir, target)
            ?: error("BuildTools produced no $target jar under $dir")
    }

    private fun existingOutput(dir: Path, target: String): Path? =
        if (dir.exists()) {
            dir.listDirectoryEntries("$target-*.jar")
                .firstOrNull { !it.fileName.toString().endsWith("-sources.jar") }
        } else null

    private fun lockFile(dir: Path): FileLock =
        FileChannel.open(
            dir.resolve(".build.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ).use { it.lock() }

    companion object {
        private val LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
