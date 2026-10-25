package xyz.wagyourtail.unimined.provider.native.metadata

import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.provider.native.manifest.VersionJson

/** Pure metadata projection used by the native provider before file materialization. */
data class ResolvedMinecraftMetadata(
    /** Parsed Mojang version document. */
    val version: VersionJson,
    /** Download references keyed by the provider artifact role. */
    val downloads: Map<ArtifactKey, VersionJson.Download>,
    /** Maven coordinates for libraries applicable to the selected platform. */
    val libraries: List<String>,
    /** Asset-index download reference, if the version has one. */
    val assetIndex: VersionJson.Download?,
    /** Raw launch document and lazy parent lookup. */
    val launch: LaunchMetadata
)
