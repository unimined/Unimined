package xyz.wagyourtail.unimined.minecraft.spi

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.MappingTree

/**
 * The provider output contract — deliberately the *only* seam between
 * `unimined-minecraft` and provider internals. Everything downstream
 * plugins consume arrives here as `Provider`s, never raw files.
 */
interface MinecraftArtifacts {

    /** Resolved jars by role — client/server/merged, sources. */
    val artifacts: Map<ArtifactKey, Provider<RegularFile>>

    /** Resolved mapping tree for remap specs. */
    val mappings: Provider<MappingTree>

    /** MC runtime dependencies (libraries). */
    val libraries: Provider<List<Dependency>>

    val assets: Provider<AssetIndex>

    /** Launch metadata for `unimined-runs`, or `null` if the provider
     *  cannot describe a launch for this environment. */
    val launch: LaunchMetadata?

}
