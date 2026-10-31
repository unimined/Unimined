package xyz.wagyourtail.unimined.provider.buildtools

import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Thin executor over Spigot's upstream `BuildTools.jar` — downloads
 * it once into the unimined cache, then runs
 * `java -jar BuildTools.jar --rev <rev> --compile <target>` inside a
 * per-target work directory and locates the produced
 * `craftbukkit-<rev>.jar` / `spigot-<rev>.jar`.
 *
 * BuildTools does its own repo cloning + maven invocation; this only
 * wraps process execution + output discovery so the provider can stay
 * a lazy artifact source.
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
    fun serverJar(target: String, rev: String): Path {
        val out = workDir.resolve("$target-$rev.jar")
        if (out.exists()) return out
        workDir.createDirectories()
        javaExec(
            "java",
            listOf(
                "-jar", buildTools().toAbsolutePath().toString(),
                "--rev", rev,
                "--compile", if (target == "spigot") "SPIGOT" else "CRAFT-BUKKIT"
            ),
            workDir
        )
        require(out.exists()) {
            "BuildTools produced no $target-$rev.jar under $workDir"
        }
        return out
    }
}
