package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Launch metadata for total-conversion dists. The root document is
 * either the dist's own published version JSON (BTA's per-version
 * `manifest.json` — a full Mojang-schema document carrying
 * libraries/natives/assetIndex/mainClass) or the base Mojang version
 * the dist rides (`b1.7.3` — the 1.x
 * `AbstractTotalConversionProductProvider.baseVersion` default).
 *
 * Serializable state only — the downloader/resolver backend is rebuilt
 * lazily inside [versionJson] at execution time.
 */
class ConversionLaunchMetadata(
    override val rootId: String,
    /** When set, [rootId] resolves to this document instead of a
     *  manifest-catalogued Mojang version (BTA's `manifest.json`). */
    private val rootDocumentUrl: String?,
    private val cacheDir: Path,
    private val offline: Boolean,
    private val refreshCache: Boolean,
    private val metadataTtlMillis: Long?,
    private val manifestUrls: List<String>
) : LaunchMetadata {

    @Transient
    private var resolver: ManifestResolver? = null

    @Transient
    private var downloader: Downloader? = null

    /** Test seam — injected backends are transient and never
     *  serialized; a deserialized instance rebuilds its own. */
    @Transient
    private var backendFactory: (() -> Pair<ManifestResolver, Downloader>)? = null

    internal fun withBackendFactory(
        factory: () -> Pair<ManifestResolver, Downloader>
    ) = apply { backendFactory = factory }

    @Synchronized
    private fun backend(): Pair<ManifestResolver, Downloader> {
        resolver?.let { r -> downloader?.let { return r to it } }
        return (backendFactory?.invoke() ?: run {
            val dl = Downloader(
                cacheDir = cacheDir,
                offline = offline,
                refreshCache = refreshCache,
                metadataTtl = metadataTtlMillis?.let(Duration::ofMillis)
                    ?: Duration.ofHours(24)
            )
            ManifestResolver(
                downloader = dl,
                metadataTtlOverride = metadataTtlMillis?.let(Duration::ofMillis),
                manifestUrls = listOf(ManifestResolver.VERSION_MANIFEST_V2) + manifestUrls
            ) to dl
        }).also { resolver = it.first; downloader = it.second }
    }

    override fun versionJson(id: String): JsonObject {
        val (resolver, downloader) = backend()
        if (id == rootId && rootDocumentUrl != null) {
            val file = downloader.fetchMetadata(
                rootDocumentUrl,
                "metadata/conversion/$id.json",
                metadataTtlMillis?.let(Duration::ofMillis)
                    ?: Duration.ofDays(7)
            )
            return Json.parseToJsonElement(Files.readString(file)).jsonObject
        }
        return resolver.versionJsonDocument(id)
    }

}
