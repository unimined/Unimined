package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val manifestUrls: List<String>,
    /** When set, [rootId]'s own document inherits the base Mojang
     *  version's `libraries` the manifest doesn't itself declare —
     *  `manifestUrl` products like BTA ride a thin manifest (the
     *  launchwrapper set only) but still need the base doc's
     *  paulscode/sound and other runtime libs. [documentPatch]
     *  applies to the merged doc, so its drops/replacements see the
     *  full set. */
    private val baseVersionId: String? = null,
    /** A post-parse rewrite for the root document — a product pins a
     *  version-scoped library correction (BTA `7.3_04`'s published
     *  manifest still declares LWJGL2 `*-babric` libs, but that build
     *  of the game requires the LWJGL3 set — the doc's `libraries`
     *  get rewritten so classpath AND natives resolve the swapped
     *  set, not just the compile classpath). */
    private val documentPatch: ((JsonObject) -> JsonObject)? = null,
) : LaunchMetadata {

    @Transient
    private var resolver: ManifestResolver? = null

    @Transient
    private var downloader: Downloader? = null

    /** Backend-injection seam — lets the constructing provider share
     *  its own resolver/downloader pair instead of this class lazily
     *  building a second one in [backend]. Injected factories are
     *  transient and never serialized; a deserialized instance
     *  rebuilds its own. Public because `core` and `main` are
     *  separate Kotlin modules. */
    @Transient
    private var backendFactory: (() -> Pair<ManifestResolver, Downloader>)? = null

    fun withBackendFactory(
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
            var doc = Json.parseToJsonElement(Files.readString(file)).jsonObject
            // merge the base version's libraries the thin manifest
            // doesn't declare — base-version runtime deps the dist
            // rides (BTA's paulscode set)
            if (baseVersionId != null) {
                val base = resolver.versionJsonDocument(baseVersionId)
                doc = mergeBaseLibraries(doc, base)
            }
            return documentPatch?.invoke(doc) ?: doc
        }
        return resolver.versionJsonDocument(id)
    }

    /** `libraries` of [base] the manifest doesn't itself declare —
     *  manifest entries win by name so its own (already-patched) set
     *  stays authoritative. */
    private fun mergeBaseLibraries(doc: JsonObject, base: JsonObject): JsonObject {
        val declared = (doc["libraries"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?.toSet() ?: return doc
        val extra = (base["libraries"] as? JsonArray)
            ?.filter { el ->
                el.jsonObject["name"]?.jsonPrimitive?.content
                    ?.let { it !in declared } == true
            } ?: emptyList()
        if (extra.isEmpty()) return doc
        return JsonObject(doc.toMutableMap().apply {
            put(
                "libraries",
                JsonArray((doc["libraries"] as JsonArray) + extra)
            )
        })
    }

}
