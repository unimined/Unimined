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
    private val metadataTtlOverride: Duration? = null
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The full version manifest, cached at [manifestTtl]. */
    fun manifest(url: String = VERSION_MANIFEST_V2): VersionManifest {
        val file = downloader.fetchMetadata(
            url, "metadata/version-manifest-v2.json", metadataTtlOverride ?: manifestTtl
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
        val ref = manifest().version(id)
        val file = downloader.fetchMetadata(
            ref.url, "metadata/versions/$id.json", metadataTtlOverride ?: versionJsonTtl
        )
        return json.parseToJsonElement(Files.readString(file)).jsonObject
    }

    companion object {
        const val VERSION_MANIFEST_V2 =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

}
