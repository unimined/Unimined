package xyz.wagyourtail.unimined.provider.legacydist

import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.util.produceCached
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Gradle-free declared-source pipeline shared by [LegacyDistProvider]
 * (which wraps these calls in `Provider`s) and
 * [LegacyDistArtifactProducer] (which calls them inside
 * artifact-transform execution) — same split as `provider-devbundle`'s
 * production object.
 *
 * The declared artifact is consumed as-is — no manifest is consulted.
 * Materialization lands in a descriptor-keyed dir under the shared
 * `native-cache` root so the config-time provider path and the
 * transform-time producer path hit one work dir: whichever runs first
 * produces, the other finds the cached jar.
 */
internal object LegacyDistProduction {

    /** Cache dir a declared source materializes under — content-keyed
     *  on the source descriptor. */
    fun distDir(source: DeclaredSource, cacheDir: Path): Path =
        cacheDir.resolve(descriptorKey(source))

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

    /**
     * Materialize [source]'s artifact and return the jar path —
     * `distDir/jarName` for file/producer sources, the downloader's
     * cache namespace for URL sources. [runProducer] is invoked for
     * `producer { }` sources with the source spec and the work dir it
     * must write `source.output` into; pass `null` where subprocesses
     * are impossible to fail those sources with a clear error.
     */
    fun materialize(
        source: DeclaredSource,
        jarName: String,
        cacheDir: Path,
        download: (url: String, name: String, sha: String?) -> Path,
        runProducer: ((DeclaredSource.Producer, Path) -> Unit)?,
        rebuild: Boolean,
        projectDir: Path? = null,
        warn: (String) -> Unit
    ): Path {
        val dir = distDir(source, cacheDir).apply { createDirectories() }
        val dest = dir.resolve(jarName)
        return when (source) {
            is DeclaredSource.LocalFile -> {
                // the env DSL absolutizes file() at declaration; a
                // programmatic relative path resolves against the
                // project dir first (provider parity), then cwd
                val src = projectDir?.resolve(source.path)
                    ?.takeIf { it.exists() }
                    ?: Path.of(source.path)
                if (!src.exists()) throw IllegalStateException(
                    "legacydist declared file not found: ${source.path}"
                )
                dest.produceCached(force = rebuild) { tmp ->
                    Files.copy(src, tmp)
                }
            }

            is DeclaredSource.Url -> {
                if (source.sha256 == null) warn(
                    "url source ${source.url} has no sha256 pin — " +
                            "cached by URL hash only"
                )
                // the downloader verifies the pin *before* publishing —
                // algorithm inferred from hex length (64 = sha256) — a
                // mismatch throws and never poisons the cache
                download(
                    source.url,
                    "legacydist/${dir.fileName}/$jarName",
                    source.sha256?.lowercase(Locale.ROOT)
                )
            }

            is DeclaredSource.Producer -> {
                if (runProducer == null) throw IllegalStateException(
                    "legacydist producer() sources cannot run inside " +
                            "the artifact transform — materialize the " +
                            "jar via file() or url()"
                )
                dest.produceCached(force = rebuild) { tmp ->
                    runProducer(source, dir)
                    val out = dir.resolve(source.output)
                    if (!out.exists()) throw IllegalStateException(
                        "legacydist producer did not emit ${source.output}"
                    )
                    Files.move(out, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            is DeclaredSource.Maven -> throw IllegalStateException(
                "legacydist maven sources are materialized to a local " +
                        "file by the environment DSL — unexpected here: " +
                        source.notation
            )
        }
    }

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray()).toHex()

}
