package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactStateLog
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.nio.file.Path
import java.util.Locale

/**
 * Artifacts over a paperweight dev bundle — the paperclip-patched
 * server jar keyed by the env's [Side] (SERVER or MERGED; the env
 * validates CLIENT away), a `mojmap` identity tree (the bundle's
 * paperclip output is already mojang-mapped), and the bundle's
 * declared dependency lists plus the paperclip's own
 * `META-INF/libraries.list` coordinates as the library channel.
 */
internal class DevBundleArtifacts(
    spec: ProductSpec,
    ctx: ProviderContext,
    bundleZip: Provider<Path>,
    bundle: Provider<DevBundle>,
    vanilla: Provider<VersionJson>,
    paperclipJar: Provider<Path>,
    patchedJar: Provider<Path>,
    dir: Path,
    downloader: Downloader
) : ProductArtifacts {

    private val key = spec.side.artifactKey()

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> =
        mapOf(key to ctx.layout.file(patchedJar.map { it.toFile() }))

    override val auxiliaryArtifacts: Map<String, Provider<RegularFile>> =
        buildMap {
            // the extracted patch tool itself — diagnostics and any
            // consumer that needs the raw paperclip
            put(
                "paperclip_jar",
                ctx.layout.file(paperclipJar.map { it.toFile() })
            )
            // reobf mappings (mojmap→spigot) — data versions 2–7;
            // absent means the provider returns an empty provider,
            // never a throw (channel consumers bind via orNull)
            put(
                "reobf_mappings",
                ctx.layout.file(ctx.providers.provider {
                    bundle.get().reobfMappingsFile?.let {
                        DevBundleProduction.extractEntry(
                            bundleZip.get(), it,
                            dir.resolve("reobf-mappings.txt")
                        )?.toFile()
                    }
                })
            )
            // bundle access transformer — data versions 2–5 only
            put(
                "access_transformer",
                ctx.layout.file(ctx.providers.provider {
                    bundle.get().accessTransformFile?.let {
                        DevBundleProduction.extractEntry(
                            bundleZip.get(), it,
                            dir.resolve("access-transformer.cfg")
                        )?.toFile()
                    }
                })
            )
            // the vanilla server_mappings channel — same role as the
            // native provider's, so `mappings { mojmap() }` resolves
            // for prod-side remaps (dev is already mojmap). The
            // bundle's artifact is server-only, so MERGED envs (whose
            // channel lookup is side-keyed to `client_mappings`)
            // get the same server map under the other key.
            val mojmapChannel = ctx.layout.file(
                ctx.providers.provider {
                    val cfg = bundle.get()
                    vanilla.get().downloads?.get("server_mappings")
                        ?.let { ref ->
                            downloader.fetch(
                                ref.url,
                                "versions/${cfg.minecraftVersion}/" +
                                        "server_mappings.txt",
                                ref.sha1?.lowercase(Locale.ROOT)
                            ).toFile()
                        }
                }
            )
            put("server_mappings", mojmapChannel)
            put("client_mappings", mojmapChannel)
        }

    // the paperclip output ships mojmap-named classes — no obf hop
    override val obfuscated: Provider<Boolean> =
        ctx.providers.provider { false }

    override val mappings: Provider<MappingTree> =
        ctx.providers.provider {
            MappingTree(listOf(Namespace("mojmap")))
        }

    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider { NamespaceMode.IDENTITY }

    override val capabilities: Provider<EnvironmentCapabilities> =
        ctx.providers.provider {
            EnvironmentCapabilities(
                minecraftVersion = bundle.get().minecraftVersion,
                platform = "devbundle",
                javaVersion = vanilla.get().javaVersion?.majorVersion ?: 8
            )
        }

    /** The produced jar carries the paperclip patch stage — the
     *  `ProductArtifacts` doc's own example shape. */
    override val completedStages: Map<ArtifactKey, Provider<ArtifactStateLog>> =
        mapOf(
            key to ctx.providers.provider {
                ArtifactStateLog.of("paperclip:binpatch")
            }
        )

    override val libraries: Provider<List<Dependency>> =
        ctx.providers.provider {
            (bundle.get().libraryCoordinates +
                    DevBundleProduction.bundledLibraryCoordinates(
                        paperclipJar.get()
                    ))
                .distinct()
                .map { ctx.dependencyFactory.create(it) }
        }

    override val extraDependencies = ctx.objects.fileCollection()

    override val extraCompileSources = ctx.objects.fileCollection()

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    override val assets: Provider<AssetIndex> =
        ctx.providers.provider { null }

    /** Paper servers launch through the patched jar + libraries, not a
     *  version-JSON profile — no [LaunchMetadata] to describe one. */
    override val launch: LaunchMetadata? = null

}

internal fun Side.artifactKey() = when (this) {
    Side.CLIENT -> ArtifactKey.CLIENT
    Side.SERVER -> ArtifactKey.SERVER
    Side.MERGED -> ArtifactKey.MERGED
    // dev bundles are server-only — "common" degrades to the same
    // patched jar it aliases under MERGED
    Side.COMMON -> ArtifactKey.COMMON
}
