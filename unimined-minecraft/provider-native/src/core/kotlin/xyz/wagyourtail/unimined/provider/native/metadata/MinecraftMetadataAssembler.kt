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
     * Assemble metadata for [version]. Launch metadata is attached by the
     * provider (see `NativeProvider`); the projection itself carries none.
     */
    fun assemble(
        version: VersionJson,
        root: JsonObject,
        platform: PlatformInfo = PlatformInfo.current(),
        obfuscatedPrefixes: List<String> = emptyList()
    ): ResolvedMinecraftMetadata {
        val downloads = buildMap {
            version.downloads?.get("client")?.let { put(ArtifactKey.CLIENT, it) }
            version.downloads?.get("server")?.let { put(ArtifactKey.SERVER, it) }
        }
        val (auxLibs, libs) = version.libraries
            .filter { it.appliesTo(platform) }
            .partition { lib ->
                obfuscatedPrefixes.any { lib.name.startsWith("$it:") }
            }
        return ResolvedMinecraftMetadata(
            version = version,
            downloads = downloads,
            libraries = libs.map { it.name },
            obfuscatedAuxArtifacts = auxLibs.mapNotNull { lib ->
                val artifact = lib.downloads?.get("artifact")
                val url = artifact?.url ?: return@mapNotNull null
                ResolvedMinecraftMetadata.AuxArtifact(
                    name = lib.name,
                    url = url,
                    sha1 = artifact.sha1
                )
            },
            assetIndex = version.assetIndex
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
