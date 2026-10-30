package xyz.wagyourtail.unimined.provider.native.manifest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Files
import java.time.Duration

/**
 * Resolves MC version metadata: the version manifest plus per-version
 * JSON, cached through [Downloader] with TTL and offline fallback.
 * Pure JVM — no Gradle imports; callers decide when/where to invoke.
 */
class ManifestResolver(
    private val downloader: Downloader,
    private val manifestTtl: Duration = Duration.ofHours(6),
    private val versionJsonTtl: Duration = Duration.ofDays(7),
    private val metadataTtlOverride: Duration? = null,
    /**
     * Ordered manifest locations — each must serve the Mojang manifest
     * schema. The first manifest containing a requested version id wins;
     * Mojang's official manifest is default so alternates only fill gaps
     * (e.g. [SKYRISING_MC_VERSIONS] restores removed alpha/beta/classic,
     * april-fools and experimental entries).
     */
    private val manifestUrls: List<String> = listOf(VERSION_MANIFEST_V2)
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The full manifest at [url], cached per host at [manifestTtl]. */
    fun manifest(url: String = VERSION_MANIFEST_V2): VersionManifest {
        val cacheName = if (url == VERSION_MANIFEST_V2)
            "metadata/version-manifest-v2.json"
        else "metadata/version-manifest-${java.net.URI(url).host}.json"
        val file = downloader.fetchMetadata(
            url, cacheName, metadataTtlOverride ?: manifestTtl
        )
        return json.decodeFromString(Files.readString(file))
    }

    /** Per-version metadata, cached at [versionJsonTtl]. */
    fun versionJson(id: String): VersionJson {
        val document = versionJsonDocument(id)
        return json.decodeFromJsonElement(document)
    }

    /** The original per-version JSON document, including unknown fields. */
    fun versionJsonDocument(id: String): JsonObject {
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) {
            "invalid Minecraft version id: $id"
        }
        val ref = manifestUrls.firstNotNullOfOrNull { url ->
            manifest(url).versions.firstOrNull { it.id == id }
        } ?: throw IllegalArgumentException(
            "unknown minecraft version '$id' in ${manifestUrls.joinToString(", ")}"
        )
        val file = downloader.fetchMetadata(
            ref.url, "metadata/versions/$id.json", metadataTtlOverride ?: versionJsonTtl
        )
        var document = json.parseToJsonElement(Files.readString(file)).jsonObject
        if (ref.details != null) {
            // enrichment channel: details fills keys the Mojang schema
            // doesn't carry; the standard document always wins
            val details = downloader.fetchMetadata(
                ref.details, "metadata/versions/$id-details.json",
                metadataTtlOverride ?: versionJsonTtl
            )
            val enriched = json.parseToJsonElement(Files.readString(details)).jsonObject
            document = JsonObject(enriched + document)
        }
        return document
    }

    companion object {
        const val VERSION_MANIFEST_V2 =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** Skyrising's manifest — Mojang-schema superset restoring
         *  versions missing from the official list (removed
         *  alpha/beta/classic, april-fools, experimental/pending).
         *  Entries may also carry `details` URLs to an extended
         *  per-version format. */
        const val SKYRISING_MC_VERSIONS =
            "https://skyrising.github.io/mc-versions/version_manifest.json"
    }

}
