package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Artifact-transform-side counterpart to [ConversionProvider] —
 * materializes the product's sided dist jar inside transform execution
 * (no Gradle services). The dist jar is already a complete Minecraft
 * jar; MERGED envs combine client+server through the standard merge.
 */
class ConversionArtifactProducer : ProductArtifactProducer {

    override val name: String = "conversion"

    /** Outranks the catch-all `native` producer (priority 0) on
     *  conversion envs — supports() is scoped to the product table. */
    override val priority: Int = 10

    override fun supports(spec: ProductSpec): Boolean =
        spec.product.removePrefix("minecraft:") in ConversionProvider.PRODUCTS

    override fun produce(spec: ProductSpec, request: ArtifactProductionRequest): Path {
        val logger = Logging.getLogger(ConversionArtifactProducer::class.java)
        val loader = spec.product.removePrefix("minecraft:")
        val product = ConversionProvider.PRODUCTS[loader]
            ?: throw IllegalStateException(
                "conversion producer does not support '$loader'"
            )
        val downloader = Downloader(
            cacheDir = request.cacheDir,
            offline = request.offline,
            refreshCache = request.refreshUniminedDownloads,
            metadataTtl = request.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                logger.warn("[Unimined/Cache] Using cached $file after refresh failed: ${error.message}")
            }
        )
        val version = spec.version.version

        // declared-source products (nsss-style: no index): the
        // declared jar IS the artifact for any key — a single dist
        // jar can't be side-split or merged
        spec.declaredSource?.source?.let { declared ->
            return when (declared) {
                is xyz.wagyourtail.unimined.api.spec.DeclaredSource.LocalFile ->
                    Path.of(declared.path).also {
                        if (!Files.exists(it)) throw IllegalStateException(
                            "conversion declared source does not exist: $it"
                        )
                    }
                is xyz.wagyourtail.unimined.api.spec.DeclaredSource.Url ->
                    downloader.fetch(
                        declared.url,
                        "${loader}/$version/declared.jar"
                    ).also { verifySha256(it, declared.sha256, logger) }
                is xyz.wagyourtail.unimined.api.spec.DeclaredSource.Maven ->
                    throw IllegalStateException(
                        "conversion declared-source maven() is materialized " +
                                "to a file before the transform — unexpected " +
                                "here: ${declared.notation}"
                    )
                is xyz.wagyourtail.unimined.api.spec.DeclaredSource.Producer ->
                    throw IllegalStateException(
                        "conversion declared-source producer() is not " +
                                "supported yet — use file() or url()"
                    )
            }
        }

        // sided jar source: BTA's manifest downloads block (sha1-verified)
        // or the product's URL template (reindev)
        fun fetchSide(side: Side): Path {
            val key = if (side == Side.CLIENT) "client" else "server"
            product.manifestUrl?.let { template ->
                val doc: VersionJson = docJson.decodeFromJsonElement(
                    Json.parseToJsonElement(
                        Files.readString(
                            downloader.fetchMetadata(
                                template(version),
                                "metadata/conversion/${loader}-$version.json",
                                request.metadataCacheTtl ?: Duration.ofDays(7)
                            )
                        )
                    )
                )
                val ref = doc.downloads?.get(key)
                    ?: throw IllegalStateException(
                        "conversion '${loader}' manifest has no $key download"
                    )
                return downloader.fetch(
                    ref.url,
                    "${loader}/$version/$key.jar",
                    ref.sha1?.lowercase()
                )
            }
            val templates = if (side == Side.CLIENT) product.clientUrls
            else product.serverUrls
            if (templates.isEmpty()) throw IllegalStateException(
                "conversion '${loader}' has no $key artifact for $version"
            )
            return fetchFirstOf(
                downloader,
                templates.map { it(version) },
                "${loader}/$version/$key.jar"
            )
        }

        // declared extraLibraries mean "replace the shaded copies" —
        // strip bundled-lib entries out of the merge INPUTS so the
        // cached merged.jar is already lean; the resolved libs ride
        // the classpath instead. Sided artifacts get the same strip as
        // a contributed pipeline step (BundledLibraryStripStepType) —
        // the pipeline only sees the produced artifact, so merge
        // inputs can only be stripped here
        fun stripMergeInput(input: Path, name: String): Path {
            if (product.extraLibraries.isEmpty() ||
                request.libraryJars.isEmpty()) return input
            val out = downloader.cacheDir.resolve(
                "${loader}/$version/$name-stripped.jar"
            )
            xyz.wagyourtail.unimined.minecraft.merge.BundledLibraryStrip
                .strip(input, request.libraryJars, out)
            return out
        }

        return when (request.artifactKey) {
            ArtifactKey.CLIENT -> fetchSide(Side.CLIENT)
            ArtifactKey.SERVER -> fetchSide(Side.SERVER)
            ArtifactKey.MERGED -> {
                if (!product.mergeable) throw IllegalStateException(
                    "conversion '${loader}' can't produce a merged " +
                            "artifact — its dists are independently-built " +
                            "forks with divergent shared classes; declare " +
                            "side = CLIENT or SERVER"
                )
                val out = downloader.cacheDir.resolve(
                    "${loader}/$version/merged.jar"
                )
                xyz.wagyourtail.unimined.minecraft.merge.JarMerger.merge(
                    stripMergeInput(fetchSide(Side.CLIENT), "client"),
                    stripMergeInput(fetchSide(Side.SERVER), "server"),
                    out,
                    xyz.wagyourtail.unimined.minecraft.merge.JarMerger.Options(
                        libraryJars = request.libraryJars,
                        includeGlobs = request.mergeIncludeGlobs,
                        excludeGlobs = request.mergeExcludeGlobs,
                        stripBundledServerLibraries =
                                request.stripBundledServerLibraries,
                        // every conversion product is FML-era (bta,
                        // reindev, nsss — all b1.7.3-based dists) —
                        // default to the era-canonical markers
                        // mergetool checksums were built against;
                        // loader/user-contributed markers win when
                        // declared (e.g. a forge claim's detected
                        // SideOnly on a forge-over-dist env)
                        sideMarkers =
                                request.sideMarkers.ifEmpty {
                                    listOf(
                                        xyz.wagyourtail.unimined.minecraft
                                            .merge.JarMerger.SideMarker
                                            .FORGE_FML
                                    )
                                },
                    )
                )
                out
            }
            else -> throw IllegalStateException(
                "conversion producer cannot produce ${request.artifactKey}"
            )
        }
    }

    private companion object {
        val docJson = Json { ignoreUnknownKeys = true }
    }

}

/** Verify a declared URL artifact's content pin — Downloader only
 *  knows sha1, so the `legacydist url(url, sha256)` pin is checked
 *  here; unpinned downloads just warn (declared-source honesty). */
internal fun verifySha256(
    file: Path, sha256: String?, logger: org.gradle.api.logging.Logger
) {
    if (sha256 == null) {
        logger.warn(
            "[Unimined] declared source $file has no sha256 pin — " +
                    "pass sha256 to url() to verify content"
        )
        return
    }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n >= 0) {
            digest.update(buf, 0, n)
            n = input.read(buf)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(sha256, ignoreCase = true)) {
        throw IllegalStateException(
            "declared source sha256 mismatch for $file: " +
                    "expected $sha256, got $actual"
        )
    }
}

/**
 * Fetch the first candidate URL that resolves — dist products can
 * list multiple URL templates (`reindev` renamed its artifact scheme
 * mid-line). Cache key is shared: the first hit pins the artifact.
 * Interrupts propagate; other failures fall through to the next
 * candidate, and an all-miss failure names every URL tried.
 */
internal fun fetchFirstOf(
    downloader: Downloader,
    urls: List<String>,
    cacheName: String
): Path {
    var last: Exception? = null
    for (url in urls) {
        try {
            return downloader.fetch(url, cacheName)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            last = e
        }
    }
    throw IllegalStateException(
        "no candidate resolved for $cacheName — tried: " +
                urls.joinToString(),
        last
    )
}
