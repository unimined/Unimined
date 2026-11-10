package xyz.wagyourtail.unimined.provider.buildtools

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Execution-time counterpart to [BuildToolsProvider]: runs Spigot's
 * `BuildTools.jar` inside the raw-artifact transform so
 * `loader("craftbukkit" | "spigot")` materializes its server jar.
 *
 * The SPI docs' "subprocess providers cannot implement this" caveat
 * assumed `ExecOperations` was required — a `ProcessBuilder` runner
 * works in transform isolation (BuildTools is just a long-running
 * JVM). Gradle toolchain services are likewise unavailable here, so
 * the per-version `javaVersions` range is satisfied by probing the
 * build JVM first and then the usual toolchain dirs
 * (`~/.gradle/jdks`, `/usr/lib/jvm`, `~/.jdks`, `JAVA_HOME`).
 */
class BuildToolsArtifactProducer : ProductArtifactProducer {

    override val name = "buildtools"

    /** Wins over [NativeArtifactProducer]'s catch-all `supports`. */
    override val priority = 1

    override fun supports(spec: ProductSpec): Boolean =
        spec.loader?.name in setOf("craftbukkit", "spigot")

    override fun produce(
        spec: ProductSpec, request: ArtifactProductionRequest
    ): Path {
        val target = spec.loader!!.name
        val rev = spec.version.version
        if (request.artifactKey == ArtifactKey.CLIENT) {
            throw GradleException(
                "unimined: $target has no client artifact — " +
                    "bukkit-family envs are server-only (side = Side.SERVER)"
            )
        }
        val logger = Logging.getLogger(BuildToolsArtifactProducer::class.java)
        val downloader = Downloader(
            cacheDir = request.cacheDir,
            offline = request.offline,
            refreshCache = request.refreshUniminedDownloads,
            metadataTtl = request.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                logger.warn(
                    "[Unimined/Cache] Using cached $file after refresh " +
                        "failed: ${error.message}"
                )
            }
        )
        val exec = BuildToolsExec(
            downloader, request.cacheDir.resolve("buildtools/$target")
        ) { exe, args, dir ->
            val log = dir.resolve("buildtools.log").toFile()
            val rc = ProcessBuilder(listOf(exe) + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start().waitFor()
            if (rc != 0) throw GradleException(
                "unimined: BuildTools for $target $rev exited $rc — " +
                    "full log at $log"
            )
        }
        return exec.serverJar(target, rev) {
            javaExecutableFor(downloader, target, rev)
        }
    }

    /** The [rev] BuildData `javaVersions` range (classfile majors →
     *  feature versions), or null when the field is absent. */
    private fun javaVersionRange(downloader: Downloader, rev: String): IntRange? =
        Regex(""""javaVersions"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""")
            .find(
                downloader.fetch(
                    "https://hub.spigotmc.org/versions/$rev.json",
                    "spigot/$rev/build-info.json"
                ).readText()
            )?.let {
                (it.groupValues[1].toInt() - 44)..(it.groupValues[2].toInt() - 44)
            }

    /** A `java` executable inside [rev]'s required major range. */
    private fun javaExecutableFor(
        downloader: Downloader, target: String, rev: String
    ): String {
        val majors = javaVersionRange(downloader, rev)
            ?: return "java" // ancient BuildData — no declared range
        if (Runtime.version().feature() in majors) return "java"
        val dirs = sequenceOf(
            Path.of(System.getProperty("java.home")),
            System.getenv("JAVA_HOME")?.let(Path::of)
        ).filterNotNull()
        val candidates = dirs.map { it.resolve("bin/java") } + sequenceOf(
            Path.of(System.getProperty("user.home"), ".gradle/jdks"),
            Path.of("/usr/lib/jvm"),
            Path.of(System.getProperty("user.home"), ".jdks")
        ).filter { Files.isDirectory(it) }
            .flatMap { it.listDirectoryEntries().asSequence() }
            .map { it.resolve("bin/java") }
        return candidates
            .filter { Files.isExecutable(it) }
            .firstOrNull { javaMajor(it) in majors }
            ?.toAbsolutePath()?.toString()
            ?: throw GradleException(
                "unimined: $target $rev needs Java " +
                    "${majors.first}-${majors.last} for BuildTools but " +
                    "no in-range JDK was found (build JVM is " +
                    "${Runtime.version().feature()}); install one under " +
                    "JAVA_HOME, ~/.gradle/jdks, /usr/lib/jvm or ~/.jdks"
            )
    }

    private fun javaMajor(java: Path): Int? = runCatching {
        val proc = ProcessBuilder(java.toString(), "-version")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        proc.waitFor()
        Regex("""version "(\d+)""").find(out)?.groupValues?.get(1)?.toInt()
    }.getOrNull()
}
