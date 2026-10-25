package xyz.wagyourtail.unimined.provider.native.metadata

import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.provider.native.manifest.VersionJson

/**
 * Projects a Mojang version JSON into the metadata consumed by the provider
 * host: jar download references, platform-applicable library coordinates,
 * asset-index reference, and the original launch document.
 */
object MinecraftMetadataAssembler {

    /**
     * Assemble metadata for [version]. [parentDocument] fetches inherited
     * version JSON lazily when the launcher resolves `inheritsFrom`.
     */
    fun assemble(
        version: VersionJson,
        root: JsonObject,
        platform: PlatformInfo = PlatformInfo.current(),
        parentDocument: (String) -> JsonObject
    ): ResolvedMinecraftMetadata {
        val downloads = buildMap {
            version.downloads?.get("client")?.let { put(ArtifactKey.CLIENT, it) }
            version.downloads?.get("server")?.let { put(ArtifactKey.SERVER, it) }
        }
        val libraries = version.libraries
            .filter { it.appliesTo(platform) }
            .map { it.name }
        return ResolvedMinecraftMetadata(
            version = version,
            downloads = downloads,
            libraries = libraries,
            assetIndex = version.assetIndex,
            launch = NativeLaunchMetadata({ root }, parentDocument)
        )
    }

    private fun VersionJson.Library.appliesTo(platform: PlatformInfo): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (rule.matches(platform)) allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun VersionJson.Rule.matches(platform: PlatformInfo): Boolean {
        val osRule = os
        if (osRule != null) {
            if (osRule.name != null && osRule.name != platform.osName) return false
            if (osRule.arch != null && !Regex(osRule.arch).containsMatchIn(platform.arch)) return false
            if (osRule.version != null && !Regex(osRule.version).containsMatchIn(platform.osVersion)) return false
        }
        return features.orEmpty().all { (key, expected) ->
            (platform.features[key] ?: false) == expected
        }
    }

}
