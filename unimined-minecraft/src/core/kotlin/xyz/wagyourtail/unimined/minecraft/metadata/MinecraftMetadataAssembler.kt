package xyz.wagyourtail.unimined.minecraft.metadata

import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson

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
            .filter { VersionLibraryRules.appliesTo(it, platform) }
            .partition { lib ->
                obfuscatedPrefixes.any { lib.name.startsWith("$it:") }
            }
        return ResolvedMinecraftMetadata(
            version = version,
            downloads = downloads,
            // natives-only entries (legacy lwjgl/twitch shape) have no
            // plain jar coordinate; their classifiers ride the natives
            // resolution channel instead of the compile/runtime classpath
            libraries = libs.mapNotNull {
                VersionLibraryRules.classpathCoordinate(it, platform)
            },
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

}
