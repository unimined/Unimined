package xyz.wagyourtail.unimined.minecraft.spi

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
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

    /** Provider/tool-specific named auxiliary outputs (e.g. NFRT result names). */
    val auxiliaryArtifacts: Map<String, Provider<RegularFile>>
        get() = emptyMap()

    /** Resolved mapping tree for remap specs. */
    val mappings: Provider<MappingTree>

    /**
     * Whether the provider's source artifact requires a namespace-changing
     * remap to serve the dev classpath. Combined with the resolved mapping
     * tree's first/last namespaces this drives dev/prod view collapse.
     */
    val namespaceMode: Provider<NamespaceMode>

    /** Effective environment feature data (versions, platform, toolchain). */
    val capabilities: Provider<EnvironmentCapabilities>

    /** MC runtime dependencies (libraries). */
    val libraries: Provider<List<Dependency>>

    /** Replacement/runtime classpath deps contributed outside [libraries]. */
    val extraDependencies: FileCollection

    /** Provider-generated sources to add to the environment's compile inputs. */
    val extraCompileSources: FileCollection

    val assets: Provider<AssetIndex>

    /** Launch metadata for `unimined-runs`, or `null` if the provider
     *  cannot describe a launch for this environment. */
    val launch: LaunchMetadata?

}
