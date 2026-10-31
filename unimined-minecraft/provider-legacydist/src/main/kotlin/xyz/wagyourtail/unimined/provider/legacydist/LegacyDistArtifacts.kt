package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactStateLog
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File

/**
 * Artifacts over a declared-source jar — the jar keyed by the env's
 * [Side], an identity mapping tree in the declared namespace, and the
 * declared `format`/`namespace` as the reported state (no real
 * provider ran; the declaration *is* the state-log contract).
 */
internal class LegacyDistArtifacts(
    spec: MinecraftSpec,
    declared: DeclaredSourceSpec,
    jar: Provider<File>,
    ctx: ProviderContext
) : MinecraftArtifacts {

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = mapOf(
        spec.side.artifactKey() to ctx.layout.file(jar)
    )

    override val auxiliaryArtifacts: Map<String, Provider<RegularFile>> =
        emptyMap()

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    override val mappings: Provider<xyz.wagyourtail.unimined.mapping.MappingTree> =
        ctx.providers.provider {
            mappingTree(declared.namespace)
        }

    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider {
            if (declared.format == "obf") NamespaceMode.REMAPPABLE
            else NamespaceMode.IDENTITY
        }

    override val capabilities = ctx.providers.provider {
        EnvironmentCapabilities(minecraftVersion = spec.version.version)
    }

    override val completedStages: Map<ArtifactKey, Provider<ArtifactStateLog>> =
        emptyMap()

    override val libraries: Provider<List<Dependency>> =
        ctx.providers.provider { emptyList() }

    override val extraDependencies = ctx.objects.fileCollection()

    override val extraCompileSources = ctx.objects.fileCollection()

    override val assets: Provider<AssetIndex> =
        ctx.providers.provider { null }

    override val launch: LaunchMetadata? = null

}
