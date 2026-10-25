package xyz.wagyourtail.unimined.provider.native.metadata

import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import xyz.wagyourtail.unimined.provider.native.manifest.ManifestResolver
import java.nio.file.Path
import java.time.Duration

/**
 * Launch metadata backed by Mojang's version documents. Holds only the
 * inputs needed to rebuild the manifest resolver — serializable state so
 * run tasks can carry it through the configuration cache and fetch
 * documents lazily at execution time.
 */
class NativeLaunchMetadata(
    override val rootId: String,
    private val cacheDir: Path,
    private val offline: Boolean,
    private val refreshCache: Boolean,
    private val metadataTtlMillis: Long?
) : LaunchMetadata {

    @Transient
    private var resolver: ManifestResolver? = null

    /** Test/provider seam — injected resolvers are transient and never
     *  serialized; a deserialized instance rebuilds its own. */
    @Transient
    private var resolverFactory: (() -> ManifestResolver)? = null

    internal fun withResolverFactory(factory: () -> ManifestResolver) = apply {
        resolverFactory = factory
    }

    @Synchronized
    private fun resolver(): ManifestResolver = resolver ?: (
        resolverFactory?.invoke() ?: ManifestResolver(
            downloader = Downloader(
                cacheDir = cacheDir,
                offline = offline,
                refreshCache = refreshCache,
                metadataTtl = metadataTtlMillis?.let(Duration::ofMillis) ?: Duration.ofHours(24)
            ),
            metadataTtlOverride = metadataTtlMillis?.let(Duration::ofMillis)
        )
    ).also { resolver = it }

    override fun versionJson(id: String): JsonObject = resolver().versionJsonDocument(id)

}
