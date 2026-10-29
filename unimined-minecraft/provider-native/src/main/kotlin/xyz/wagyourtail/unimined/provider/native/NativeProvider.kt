package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeMinecraftSpec
import xyz.wagyourtail.unimined.minecraft.spi.*
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import xyz.wagyourtail.unimined.provider.native.manifest.ManifestResolver
import xyz.wagyourtail.unimined.provider.native.merge.JarMerger
import xyz.wagyourtail.unimined.provider.native.metadata.NativeLaunchMetadata
import xyz.wagyourtail.unimined.provider.native.metadata.ResolvedMinecraftMetadata
import java.nio.file.Path
import java.time.Duration
import java.util.*

/**
 * Mojang's native version provider. It resolves bare Minecraft environments;
 * modloader environments are handled by their dedicated provider plugins.
 * Network and file work is deferred behind the returned Gradle providers.
 *
 * The injectable [downloaderFactory] is primarily a deterministic test seam.
 */
class NativeProvider(
    private val downloaderFactory: (ProviderContext, Path) -> Downloader = { context, cacheDir ->
        Downloader(
            cacheDir = cacheDir,
            offline = context.offline,
            refreshCache = context.refreshUniminedDownloads,
            metadataTtl = context.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                context.logger.warn("[Unimined/Cache] Using cached $file after refresh failed: ${error.message}")
            }
        )
    }
) : MinecraftProvider {

    override val name: String = "native"

    override fun supports(spec: MinecraftSpec): Boolean = spec.loader == null

    override fun resolve(spec: MinecraftSpec, ctx: ProviderContext): MinecraftArtifacts {
        // `supports` gates *default* selection to bare Minecraft, but a
        // loader transform may explicitly select this provider for the
        // environment's vanilla-side artifact (e.g. fabric) — resolve
        // honors the spec as given.
        val versionId = spec.version.version
        val cacheDir = ctx.layout.projectDirectory
            .dir(".gradle/unimined/native-cache")
            .asFile.toPath()
        val downloader = downloaderFactory(ctx, cacheDir)
        val resolver = ManifestResolver(
            downloader = downloader,
            metadataTtlOverride = ctx.metadataCacheTtl
        )

        fun metadata(): ResolvedMinecraftMetadata =
            NativeArtifactProduction.metadata(versionId, resolver)

        // ProviderContext cannot resolve configurations, so the eager
        // merge honors the spec's glob/flag state but never the
        // minecraftLibraries membership strip — the artifact-transform
        // path carries the resolved library set and is the production
        // merge consumers actually resolve.
        val mergeOptions = (spec as? RuntimeMinecraftSpec)?.let { rs ->
            JarMerger.Options(
                includeGlobs = rs.mergeIncludeGlobs,
                excludeGlobs = rs.mergeExcludeGlobs,
                stripBundledServerLibraries = rs.stripBundledServerLibraries
            )
        } ?: JarMerger.Options()

        fun downloadedJar(key: ArtifactKey): Path =
            NativeArtifactProduction.produce(
                versionId, key, downloader, resolver,
                ctx.rebuildUniminedArtifacts, mergeOptions
            )

        fun artifactProvider(key: ArtifactKey): Provider<RegularFile> =
            ctx.layout.file(ctx.providers.provider { downloadedJar(key).toFile() })

        val artifacts = linkedMapOf<ArtifactKey, Provider<RegularFile>>()
        when (spec.side) {
            Side.CLIENT -> artifacts[ArtifactKey.CLIENT] = artifactProvider(ArtifactKey.CLIENT)
            Side.SERVER -> artifacts[ArtifactKey.SERVER] = artifactProvider(ArtifactKey.SERVER)
            Side.MERGED -> artifacts[ArtifactKey.MERGED] = artifactProvider(ArtifactKey.MERGED)
        }

        val libraries: Provider<List<org.gradle.api.artifacts.Dependency>> =
            ctx.providers.provider {
                metadata().libraries.map { ctx.dependencyFactory.create(it) }
            }
        val assetIndex = NativeAssetIndex(
            id = versionId,
            file = ctx.layout.file(ctx.providers.provider {
                val ref = metadata().assetIndex
                if (ref == null) {
                    NativeArtifactProduction.emptyAssetIndex(cacheDir).toFile()
                } else {
                    downloader.fetch(
                        ref.url,
                        "assets/index-${ref.sha1 ?: ref.id ?: "unknown"}.json",
                        ref.sha1?.lowercase(Locale.ROOT)
                    ).toFile()
                }
            })
        )
        val launch = NativeLaunchMetadata(
            rootId = versionId,
            cacheDir = cacheDir,
            offline = ctx.offline,
            refreshCache = ctx.refreshUniminedDownloads,
            metadataTtlMillis = ctx.metadataCacheTtl?.toMillis()
        ).withResolverFactory { resolver }

        val capabilities = ctx.providers.provider {
            EnvironmentCapabilities(
                minecraftVersion = versionId,
                platform = "vanilla",
                javaVersion = metadata().version.javaVersion?.majorVersion ?: 8
            )
        }

        val auxiliary = mutableMapOf<String, Provider<RegularFile>>()
        // raw side jars — needed by loader pipelines (e.g. forge's
        // mcpconfig clean jar) regardless of the environment side
        for (key in listOf(ArtifactKey.CLIENT, ArtifactKey.SERVER)) {
            auxiliary[key.name.lowercase(Locale.ROOT) + "_jar"] =
                ctx.layout.file(ctx.providers.provider {
                    downloadedJar(key).toFile()
                })
        }
        for (channel in listOf("client_mappings", "server_mappings")) {
            auxiliary[channel] = ctx.layout.file(ctx.providers.provider {
                val ref = metadata().version.downloads?.get(channel)
                    ?: throw GradleException(
                        "Minecraft $versionId has no $channel download " +
                                "(mojmap channel requires a version with official mappings)"
                    )
                downloader.fetch(
                    ref.url,
                    "versions/$versionId/$channel.txt",
                    ref.sha1?.lowercase(Locale.ROOT)
                ).toFile()
            })
        }

        return NativeMinecraftArtifacts(
            artifacts = artifacts,
            auxiliaryArtifacts = auxiliary,
            mappings = ctx.providers.provider { OfficialIdentityMappingTree },
            namespaceMode = ctx.providers.provider { NamespaceMode.REMAPPABLE },
            capabilities = capabilities,
            libraries = libraries,
            extraDependencies = ctx.objects.fileCollection(),
            extraCompileSources = ctx.objects.fileCollection(),
            assets = ctx.providers.provider { assetIndex },
            launch = launch
        )
    }

}

private class NativeMinecraftArtifacts(
    override val artifacts: Map<ArtifactKey, Provider<RegularFile>>,
    override val auxiliaryArtifacts: Map<String, Provider<RegularFile>>,
    override val mappings: Provider<MappingTree>,
    override val namespaceMode: Provider<NamespaceMode>,
    override val capabilities: Provider<EnvironmentCapabilities>,
    override val libraries: Provider<List<org.gradle.api.artifacts.Dependency>>,
    override val extraDependencies: org.gradle.api.file.FileCollection,
    override val extraCompileSources: org.gradle.api.file.FileCollection,
    override val assets: Provider<AssetIndex>,
    override val launch: LaunchMetadata
) : MinecraftArtifacts

private class NativeAssetIndex(
    override val id: String,
    override val file: Provider<RegularFile>
) : AssetIndex

/** Identity-only official namespace until a user mapping source is composed. */
private val OfficialIdentityMappingTree: MappingTree =
    MappingTree(listOf(Namespace("official")))
