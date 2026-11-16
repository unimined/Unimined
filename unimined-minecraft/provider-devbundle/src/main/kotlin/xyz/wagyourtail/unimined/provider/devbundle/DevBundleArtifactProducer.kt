package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * Execution-time counterpart to [DevBundleProvider]: materializes the
 * declared dev-bundle zip and runs its paperclip inside the
 * raw-artifact transform — `ProcessBuilder` like
 * `provider-buildtools` (no `ExecOperations` in transform isolation,
 * no `JavaToolchainService` either, so the Java pick probes the build
 * JVM then the usual JDK dirs).
 *
 * `supports` claims the same marker [DevBundleProvider] does — the
 * transform-side spec carries `DeclaredSourceSpec` wholesale (source +
 * `format`), so `format = "devbundle"` declarations negotiate here
 * while untagged declared sources fall to `legacydist`'s floor claim
 * (4). `conversion`'s product-table claims still outrank this producer
 * on its own products.
 */
class DevBundleArtifactProducer : ProductArtifactProducer {

    override val name: String = DevBundleProvider.NAME

    /** Above `legacydist`'s declared-source floor (4), below
     *  `conversion`'s product-table claim (10). */
    override val priority: Int = 5

    override val artifactFormatVersion: Int = 1

    override fun supports(spec: ProductSpec): Boolean =
        spec.declaredSource?.format == DevBundleProvider.FORMAT

    override fun produce(spec: ProductSpec, request: ArtifactProductionRequest): Path {
        if (request.artifactKey == ArtifactKey.CLIENT) {
            throw IllegalStateException(
                "unimined: dev bundles are server-only artifacts — " +
                        "set side = Side.SERVER or MERGED"
            )
        }
        val declared = spec.declaredSource ?: throw IllegalStateException(
            "unimined: devbundle requires a declaredSource — declare " +
                    "the bundle via legacydist { file(...) } or a dist " +
                    "block's file()/url()"
        )
        val logger = Logging.getLogger(DevBundleArtifactProducer::class.java)
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
        val resolver = ManifestResolver(
            downloader = downloader,
            metadataTtlOverride = request.metadataCacheTtl,
            manifestUrls = listOf(ManifestResolver.VERSION_MANIFEST_V2) +
                    spec.manifestUrls
        )
        val bundleZip = DevBundleProduction.materialize(
            declared.source, request.cacheDir,
            download = downloader::fetch,
            runProducer = null,
            rebuild = request.rebuildUniminedArtifacts
        ) { msg -> logger.warn("[Unimined/devbundle] $msg") }
        val bundle = DevBundleProduction.readConfig(bundleZip)
        val version = resolver.versionJson(bundle.minecraftVersion)
        val ref = version.downloads?.get("server")
            ?: throw IllegalStateException(
                "unimined: Minecraft ${bundle.minecraftVersion} has no " +
                        "server jar download"
            )
        val vanillaJar = downloader.fetch(
            ref.url,
            "versions/${bundle.minecraftVersion}/server.jar",
            ref.sha1?.lowercase(Locale.ROOT)
        )
        return DevBundleProduction.produceDevJar(
            bundleZip, bundle,
            DevBundleProduction.bundleDir(declared.source, request.cacheDir),
            vanillaJar,
            javaExecutable = DevBundleProduction.javaExecutableFor(
                version.javaVersion?.majorVersion ?: 8
            ),
            runJava = DevBundleProduction.processRunner,
            rebuild = request.rebuildUniminedArtifacts
        )
    }

}
