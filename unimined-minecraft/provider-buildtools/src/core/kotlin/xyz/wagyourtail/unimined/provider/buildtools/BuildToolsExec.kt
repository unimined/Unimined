package xyz.wagyourtail.unimined.provider.buildtools

import xyz.wagyourtail.unimined.minecraft.download.Downloader
import java.nio.channels.FileChannel
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
     * like `1.20.4`) — the maven module output under `…/target/` with
     * the real server classes (`org.bukkit.craftbukkit.Main`,
     * `net.minecraft.server.*`), not the bootstrap launcher jar
     * BuildTools also drops in the work-dir root. Idempotent —
     * skips when the output already exists.
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
                                target.uppercase()
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

    /**
     * The bootstrap launcher jar (`<target>-<rev>.jar` in the work
     * dir root) — a paperclip-style container carrying
     * `META-INF/libraries/` + `META-INF/versions/`, which is where
     * the bukkit api and the server's runtime library set live.
     */
    fun bootstrapJar(dir: Path, target: String): Path? =
        if (dir.exists()) {
            dir.listDirectoryEntries("$target-*.jar")
                .firstOrNull { !it.fileName.toString()
                    .endsWith("-sources.jar") }
        } else null

    /**
     * The runtime library set the bootstrap embeds: rows of
     * `META-INF/libraries.list` shaped `<sha256>\t*<name>.jar` mark
     * embedded jars under `META-INF/libraries/` — extracted to
     * [outDir] and returned as file-dep paths (the set includes the
     * bukkit api jar itself — `bukkit-<rev>-R0.1-SNAPSHOT.jar`).
     * Empty when the bootstrap predates the bundler layout.
     */
    fun embeddedLibraries(target: String, rev: String, outDir: Path): List<Path> {
        val bootstrap = bootstrapJar(workDir.resolve("$target-$rev"), target)
            ?: return emptyList()
        return java.util.zip.ZipFile(bootstrap.toFile()).use { zip ->
            val list = zip.getEntry("META-INF/libraries.list")
                ?: return emptyList()
            zip.getInputStream(list).bufferedReader().readLines()
                .mapNotNull { line ->
                    line.split(Regex("\\s+"))
                        .firstOrNull { it.startsWith("*") && it.endsWith(".jar") }
                        ?.removePrefix("*")
                }
                .mapNotNull { name ->
                    // rows can name jars carried outside
                    // `libraries/` (the vanilla server jar lives
                    // under `versions/`) — those are not classpath
                    // members, the impl jar already shades them
                    val entry = zip.getEntry("META-INF/libraries/$name")
                        ?: return@mapNotNull null
                    val dest = outDir.resolve(name)
                    if (!dest.exists()) {
                        dest.parent.createDirectories()
                        zip.getInputStream(entry).use { input ->
                            java.nio.file.Files.copy(input, dest)
                        }
                    }
                    dest
                }
        }
    }

    /** The maven-module output jar — `…/target/<target>-*.jar`,
     *  skipping classifier jars and the `original-*` pre-shade
     *  copy. `target/` confinement keeps the root bootstrap and the
     *  bukkit-api sibling module out of the match. */
    private fun existingOutput(dir: Path, target: String): Path? =
        if (dir.exists()) {
            java.nio.file.Files.walk(dir).use { stream ->
                stream.filter {
                    it.parent?.fileName?.toString() == "target" &&
                        it.fileName.toString().let { n ->
                            n.startsWith("$target-") &&
                                n.getOrNull(target.length + 1)?.isDigit() == true &&
                                n.endsWith(".jar") &&
                                !n.endsWith("-sources.jar") &&
                                !n.endsWith("-bootstrap.jar") &&
                                !n.endsWith("-javadoc.jar") &&
                                !n.endsWith("-tests.jar")
                        }
                }.findFirst().orElse(null)
            }
        } else null

    companion object {
        private val LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
