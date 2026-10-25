package xyz.wagyourtail.unimined.provider.native.manifest

import kotlinx.serialization.Serializable

/**
 * Per-version metadata (`versions/<id>.json`) — jar download locations,
 * library coordinates, assets, launch info.
 */
@Serializable
class VersionJson(
    val id: String,
    val type: String,
    val downloads: Map<String, Download>? = null,
    val assetIndex: Download? = null,
    val libraries: List<Library> = emptyList(),
    val mainClass: String = "",
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null
) {

    @Serializable
    class Download(
        val url: String,
        val sha1: String? = null,
        val size: Long? = null,
        val id: String? = null
    )

    @Serializable
    class Library(
        val name: String,
        val downloads: Map<String, ArtifactOrRules>? = null,
        val rules: List<Rule> = emptyList(),
        val natives: Map<String, String>? = null,
        val extract: Extract? = null
    )

    @Serializable
    class Rule(
        val action: String,
        val os: OsRule? = null,
        val features: Map<String, Boolean>? = null
    )

    @Serializable
    class OsRule(
        val name: String? = null,
        val arch: String? = null,
        val version: String? = null
    )

    @Serializable
    class Extract(
        val exclude: List<String> = emptyList()
    )

    @Serializable
    class ArtifactOrRules(
        val url: String? = null,
        val sha1: String? = null,
        val path: String? = null
    )

    @Serializable
    class Arguments(
        val game: List<kotlinx.serialization.json.JsonElement>? = null,
        val jvm: List<kotlinx.serialization.json.JsonElement>? = null
    )

}
