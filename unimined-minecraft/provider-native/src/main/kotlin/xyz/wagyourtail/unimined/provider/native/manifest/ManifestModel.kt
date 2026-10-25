package xyz.wagyourtail.unimined.provider.native.manifest

import kotlinx.serialization.Serializable

/**
 * The Mojang version manifest (`version_manifest_v2.json`) — the index of
 * all known MC versions and where their per-version metadata lives.
 */
@Serializable
class VersionManifest(
    val latest: Latest,
    val versions: List<VersionRef>
) {

    @Serializable
    class Latest(val release: String, val snapshot: String)

    @Serializable
    class VersionRef(
        val id: String,
        val type: String,
        val url: String,
        val time: String,
        val releaseTime: String
    )

    /** Find a version's metadata url by id (`"1.20.1"`, `"23w13a"`, ...). */
    fun version(id: String): VersionRef =
        versions.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException(
                "unknown minecraft version '$id' in manifest"
            )

}
