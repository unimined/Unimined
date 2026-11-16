package xyz.wagyourtail.unimined.provider.devbundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.util.produceCached
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries

/**
 * Gradle-free dev-bundle pipeline shared by [DevBundleProvider] (which
 * wraps these calls in `Provider`s) and [DevBundleArtifactProducer]
 * (which calls them inside artifact-transform execution) — same split
 * as `provider-native`'s production object.
 *
 * A paperweight dev bundle is a zip with `data-version.txt` +
 * `config.json` at the root, `data/` artifacts, and `patches/` source
 * patches. The consumable artifact is the bundle's mojang-mapped
 * paperclip jar run against the vanilla server — the output is an
 * already-patched, already-mojmap server jar.
 */
internal object DevBundleProduction {

    private val json = Json { ignoreUnknownKeys = true }

    /** Data versions paperweight's own reader accepts (2–8); ours is a
     *  single lenient model covering all of them. */
    val SUPPORTED_DATA_VERSIONS = 2..8

    /** Cache dir a declared source materializes/produces under —
     *  content-keyed on the source descriptor like `legacydist`. */
    fun bundleDir(source: DeclaredSource, cacheDir: Path): Path =
        cacheDir.resolve("devbundle").resolve(descriptorKey(source))

    private fun descriptorKey(source: DeclaredSource): String =
        sha256Hex(
            when (source) {
                is DeclaredSource.LocalFile -> "file:${source.path}"
                is DeclaredSource.Maven -> "maven:${source.notation}"
                is DeclaredSource.Url -> "url:${source.sha256 ?: source.url}"
                is DeclaredSource.Producer ->
                    "producer:${source.executable}:${source.args.joinToString(",")}"
            }
        )

    /** Parse `data-version.txt` + `config.json` from the bundle zip. */
    fun readConfig(bundleZip: Path): DevBundle {
        java.util.zip.ZipFile(bundleZip.toFile()).use { zip ->
            val dvEntry = zip.getEntry("data-version.txt")
                ?: throw IllegalStateException(
                    "declared source $bundleZip is not a paperweight dev " +
                            "bundle (no data-version.txt)"
                )
            val dataVersion = zip.getInputStream(dvEntry).use {
                it.readBytes().decodeToString().trim().toIntOrNull()
            } ?: throw IllegalStateException(
                "dev bundle $bundleZip has an unreadable data-version.txt"
            )
            if (dataVersion !in SUPPORTED_DATA_VERSIONS) {
                throw IllegalStateException(
                    "dev bundle $bundleZip is data version $dataVersion; " +
                            "supported versions are $SUPPORTED_DATA_VERSIONS"
                )
            }
            val configEntry = zip.getEntry("config.json")
                ?: throw IllegalStateException(
                    "dev bundle $bundleZip has no config.json"
                )
            val config = zip.getInputStream(configEntry).use {
                json.decodeFromString<DevBundleConfig>(
                    it.readBytes().decodeToString()
                )
            }
            return DevBundle(dataVersion, config)
        }
    }

    /**
     * Materialize the declared source into the content-keyed bundle
     * cache. [runProducer] is invoked for `producer { }` sources —
     * `null` on the artifact-transform path (no exec there), which
     * fails those sources with a clear error.
     */
    fun materialize(
        source: DeclaredSource,
        cacheDir: Path,
        download: (url: String, name: String, sha: String?) -> Path,
        runProducer: ((DeclaredSource.Producer, Path) -> Unit)?,
        rebuild: Boolean,
        warn: (String) -> Unit
    ): Path {
        val dir = bundleDir(source, cacheDir).apply { createDirectories() }
        val dest = dir.resolve("bundle.zip")
        return when (source) {
            is DeclaredSource.LocalFile -> {
                val src = Path.of(source.path)
                if (!src.exists()) throw IllegalStateException(
                    "devbundle declared file not found: ${source.path}"
                )
                dest.produceCached(force = rebuild) { tmp ->
                    Files.copy(src, tmp)
                }
            }

            is DeclaredSource.Url -> {
                if (source.sha256 == null) warn(
                    "devbundle url source ${source.url} has no sha256 pin " +
                            "— cached by URL hash only"
                )
                // the fetch verifies the pin *before* publishing —
                // algorithm is inferred from hex length (64 = sha256),
                // a mismatch throws and never poisons the cache
                download(
                    source.url,
                    "devbundle/${dir.fileName}/bundle.zip",
                    source.sha256
                )
            }

            is DeclaredSource.Producer -> {
                if (runProducer == null) throw IllegalStateException(
                    "devbundle producer() sources cannot run inside the " +
                            "artifact transform — materialize the bundle " +
                            "via file() or url()"
                )
                dest.produceCached(force = rebuild) { tmp ->
                    runProducer(source, dir)
                    val out = dir.resolve(source.output)
                    if (!out.exists()) throw IllegalStateException(
                        "devbundle producer did not emit ${source.output}"
                    )
                    Files.move(out, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            is DeclaredSource.Maven -> throw IllegalStateException(
                "devbundle maven sources are materialized to a local " +
                        "file by the environment DSL — unexpected here: " +
                        source.notation
            )
        }
    }

    /** Copy [entryPath] out of the bundle zip to [dest] — `null`
     *  (and nothing written) when [optional] and the entry is absent. */
    fun extractEntry(
        bundleZip: Path,
        entryPath: String,
        dest: Path,
        optional: Boolean = false
    ): Path? {
        java.util.zip.ZipFile(bundleZip.toFile()).use { zip ->
            val entry = zip.getEntry(entryPath) ?: run {
                if (optional) return null
                throw IllegalStateException(
                    "dev bundle is missing expected entry '$entryPath'"
                )
            }
            dest.produceCached { tmp ->
                tmp.parent.createDirectories()
                zip.getInputStream(entry).use { Files.copy(it, tmp) }
            }
            return dest
        }
    }

    /**
     * Run the bundle's mojang-mapped paperclip against [vanillaJar]
     * (the raw Mojang `server.jar` download — bundler wrapper included;
     * paperclip extracts it itself) and return the patched,
     * mojang-mapped server jar. Mirrors paperweight's
     * `RunPaperclipAction`: `-Dpaperclip.patchonly=true` in a work dir
     * with `cache/mojang_<version>.jar` pre-staged, output located via
     * the paperclip's own `version.json`/`versions.list` (bundler-era)
     * or the `cache/patched*` file (pre-bundler).
     */
    fun produceDevJar(
        bundleZip: Path,
        bundle: DevBundle,
        dir: Path,
        vanillaJar: Path,
        javaExecutable: String?,
        runJava: JavaRunner,
        rebuild: Boolean
    ): Path {
        val paperclip = extractEntry(
            bundleZip, bundle.mojangMappedPaperclipFile,
            dir.resolve("paperclip.jar")
        )!!
        val out = dir.resolve("patched-server.jar")
        out.produceCached(force = rebuild) { tmp ->
            val work = Files.createTempDirectory(dir, "paperclip-work-")
            try {
                val cache = work.resolve("cache").apply { createDirectories() }
                Files.copy(
                    vanillaJar,
                    cache.resolve("mojang_${bundle.minecraftVersion}.jar"),
                    StandardCopyOption.REPLACE_EXISTING
                )
                runJava.run(
                    JavaInvocation(
                        executable = javaExecutable,
                        classpath = listOf(paperclip),
                        mainClass = manifestMainClass(paperclip),
                        jvmArgs = listOf("-Dpaperclip.patchonly=true"),
                        workingDir = work,
                        logFile = dir.resolve("paperclip.log")
                    )
                )
                Files.copy(locateOutput(paperclip, work), tmp)
            } finally {
                work.toFile().deleteRecursively()
            }
        }
        return out
    }

    /** Find the paperclip's patched-server output inside [work]. */
    private fun locateOutput(paperclip: Path, work: Path): Path {
        java.util.zip.ZipFile(paperclip.toFile()).use { zip ->
            val versionsList = zip.getEntry("META-INF/versions.list")
            val versionJson = zip.getEntry("version.json")
            if (versionsList != null && versionJson != null) {
                // bundler-era paperclip emits versions/<path>; the row
                // is keyed by the paperclip's own version id
                val id = Json.parseToJsonElement(
                    zip.getInputStream(versionJson).use {
                        it.readBytes().decodeToString()
                    }
                ).jsonObject["id"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException(
                        "paperclip version.json has no id"
                    )
                val rel = zip.getInputStream(versionsList).use {
                    it.readBytes().decodeToString().lines()
                }.filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        line.split('\t').let { cols ->
                            cols.getOrNull(1)?.let { it to cols.getOrNull(2) }
                        }
                    }.toMap()[id]
                    ?: throw IllegalStateException(
                        "paperclip versions.list has no entry for '$id'"
                    )
                return work.resolve("versions/$rel").also {
                    if (!it.exists()) throw IllegalStateException(
                        "paperclip did not produce versions/$rel under $work"
                    )
                }
            }
        }
        // pre-bundler paperclip writes the patched jar next to the
        // staged vanilla jar
        val cache = work.resolve("cache")
        return cache.listDirectoryEntries()
            .firstOrNull { it.fileName.toString().startsWith("patched") }
            ?: throw IllegalStateException(
                "paperclip produced no patched jar under $cache"
            )
    }

    /** `Main-Class` of a tool jar, for classpath-style invocation. */
    fun manifestMainClass(jar: Path): String =
        java.util.jar.JarFile(jar.toFile()).use {
            it.manifest?.mainAttributes?.getValue("Main-Class")
                ?: throw IllegalStateException(
                    "dev bundle paperclip jar $jar has no Main-Class " +
                            "manifest attribute"
                )
        }

    /** Maven coordinates the bundler-era paperclip extracts at
     *  runtime (`META-INF/libraries.list` ids) — the 6+ data
     *  versions' runtime library channel, since their config carries
     *  no dependency list. */
    fun bundledLibraryCoordinates(paperclip: Path): List<String> {
        java.util.zip.ZipFile(paperclip.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/libraries.list")
                ?: return emptyList()
            return zip.getInputStream(entry).use {
                it.readBytes().decodeToString().lines()
            }.mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) null
                else line.split('\t').getOrNull(1)
            }
        }
    }

    /** A single `java` invocation — classpath + main-class style, the
     *  shape paperweight's `runJar` uses. */
    data class JavaInvocation(
        /** Absolute java executable; `null` resolves the current JVM's. */
        val executable: String?,
        val classpath: List<Path>,
        val mainClass: String,
        val jvmArgs: List<String>,
        val workingDir: Path,
        val logFile: Path,
        val args: List<String> = emptyList()
    )

    /** Java subprocess runner — throws on non-zero exit. */
    fun interface JavaRunner {
        fun run(invocation: JavaInvocation)
    }

    /** `ProcessBuilder`-backed [JavaRunner] — usable inside artifact
     *  transforms (no `ExecOperations`) and in tests. */
    val processRunner = JavaRunner { inv ->
        inv.logFile.parent.createDirectories()
        val exe = inv.executable
            ?: Path.of(System.getProperty("java.home"), "bin", "java")
                .toString()
        val command = listOf(exe) + inv.jvmArgs + listOf(
            "-classpath",
            inv.classpath.joinToString(File.pathSeparator) {
                it.toAbsolutePath().toString()
            },
            inv.mainClass
        ) + inv.args
        val rc = ProcessBuilder(command)
            .directory(inv.workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(inv.logFile.toFile())
            .start().waitFor()
        if (rc != 0) throw IllegalStateException(
            "devbundle java invocation failed with exit code $rc " +
                    "— log at ${inv.logFile}"
        )
    }

    /** A `java` executable whose major is at least [required] —
     *  transform-side twin of the provider's toolchain pick (no
     *  `JavaToolchainService` inside artifact transforms): prefer the
     *  build JVM, then probe the usual JDK dirs. */
    fun javaExecutableFor(required: Int): String {
        if (Runtime.version().feature() >= required) return "java"
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
            .filter { it.isExecutable() }
            .firstOrNull { javaMajor(it)?.let { m -> m >= required } == true }
            ?.toAbsolutePath()?.toString()
            ?: throw IllegalStateException(
                "devbundle requires Java $required+ but no qualifying " +
                        "JDK was found (build JVM is " +
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

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256").digest(
            data.toByteArray()
        ).toHex()

}
