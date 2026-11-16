package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.nio.file.Path
import java.time.Duration

/**
 * Execution-time counterpart to [LegacyDistProvider]: materializes the
 * declared-source artifact inside the raw-artifact transform — local
 * file/url sources into the shared `native-cache`, `producer()` sources
 * via a `ProcessBuilder` runner (`BuildToolsArtifactProducer` proved a
 * subprocess works in transform isolation; the descriptor-keyed cache
 * is the same "declared inputs are the contract" model BuildTools'
 * opaque output already carries).
 *
 * `supports` is the declared-source floor: it claims every spec
 * carrying a `declaredSource` at a priority below `devbundle`'s
 * format-marker claim (5) so tagged declared sources still reach their
 * specialized producer, and above `native`'s catch-all (0) so a plain
 * declared jar never falls through to the vanilla pipeline.
 */
class LegacyDistArtifactProducer : ProductArtifactProducer {

    override val name: String = "legacydist"

    /** Below `devbundle`'s marker claim (5), above `native`'s
     *  catch-all (0). */
    override val priority: Int = 4

    /** 1 = initial implementation. */
    override val artifactFormatVersion: Int = 1

    override fun supports(spec: ProductSpec): Boolean =
        spec.declaredSource != null

    override fun produce(spec: ProductSpec, request: ArtifactProductionRequest): Path {
        val declared = spec.declaredSource ?: throw IllegalStateException(
            "unimined: legacydist requires a declaredSource — declare " +
                    "the jar via legacydist { file(...) } or a dist " +
                    "block's file()/url()"
        )
        val logger = Logging.getLogger(LegacyDistArtifactProducer::class.java)
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
        return LegacyDistProduction.materialize(
            declared.source,
            "${spec.environmentId}-${spec.version.version}.jar",
            request.cacheDir,
            download = downloader::fetch,
            runProducer = { source, workDir ->
                // ExecOperations is unavailable in transform isolation
                // — a ProcessBuilder twin mirrors the provider's
                // exec shape (workingDir + executable + jvmArgs + args)
                val log = workDir.resolve("producer.log").toFile()
                val rc = ProcessBuilder(
                    listOf(source.executable) + source.jvmArgs + source.args
                ).directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start().waitFor()
                if (rc != 0) throw IllegalStateException(
                    "unimined: legacydist producer " +
                            "'${source.executable}' exited $rc — full " +
                            "log at $log"
                )
            },
            rebuild = request.rebuildUniminedArtifacts
        ) { msg -> logger.warn("[Unimined/legacydist] $msg") }
    }

}
