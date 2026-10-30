package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifactProducer
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import xyz.wagyourtail.unimined.provider.native.manifest.ManifestResolver
import xyz.wagyourtail.unimined.provider.native.merge.JarMerger
import java.nio.file.Path
import java.time.Duration

/**
 * Produces the environment's raw artifact (client/server/merged jar)
 * inside artifact-transform execution — the [NativeProvider] download and
 * merge machinery without Gradle services.
 */
class NativeArtifactProducer : MinecraftArtifactProducer {

    override val name: String = "native"

    /** 2 = merge strips server-bundled `minecraftLibraries` entries. */
    override val artifactFormatVersion: Int = 2

    override fun supports(spec: MinecraftSpec): Boolean = true

    override fun produce(spec: MinecraftSpec, request: ArtifactProductionRequest): Path {
        val logger = Logging.getLogger(NativeArtifactProducer::class.java)
        val downloader = Downloader(
            cacheDir = request.cacheDir,
            offline = request.offline,
            refreshCache = request.refreshUniminedDownloads,
            metadataTtl = request.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                logger.warn("[Unimined/Cache] Using cached $file after refresh failed: ${error.message}")
            }
        )
        val resolver = ManifestResolver(
            downloader = downloader,
            metadataTtlOverride = request.metadataCacheTtl,
            manifestUrls = listOf(ManifestResolver.VERSION_MANIFEST_V2) + spec.manifestUrls
        )
        return NativeArtifactProduction.produce(
            versionId = spec.version.version,
            key = request.artifactKey,
            downloader = downloader,
            resolver = resolver,
            rebuild = request.rebuildUniminedArtifacts,
            mergeOptions = JarMerger.Options(
                libraryJars = request.libraryJars,
                includeGlobs = request.mergeIncludeGlobs,
                excludeGlobs = request.mergeExcludeGlobs,
                stripBundledServerLibraries = request.stripBundledServerLibraries
            )
        )
    }

}
