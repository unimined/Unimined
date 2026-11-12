package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.metadata.MinecraftMetadataAssembler
import java.io.File
import java.util.Locale

/**
 * Artifacts over total-conversion dist jars — sided jars keyed by
 * role, an identity mapping tree in `official`, and
 * [NamespaceMode.IDENTITY] throughout (dist classes are already in
 * the vanilla shape — no remap step).
 *
 * Libraries, asset index and launch metadata come off the product's
 * effective version document: BTA's own `manifest.json` or the base
 * Mojang version JSON (`b1.7.3`) for reindev/nsss.
 */
internal class ConversionArtifacts(
    private val spec: ProductSpec,
    jars: Provider<Map<Side, File>>,
    private val rootDocument: Provider<JsonObject>,
    private val downloader: Downloader,
    override val launch: LaunchMetadata,
    ctx: ProviderContext
) : ProductArtifacts {

    private val docJson = Json { ignoreUnknownKeys = true }

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> =
        buildMap {
            put(
                spec.side.artifactKey(),
                jars.map { it.getValue(
                    if (spec.side == Side.MERGED) Side.CLIENT else spec.side
                ) }.let(ctx.layout::file)
            )
            // sided fetches for a MERGED env also expose the raw sides
            // so the merge step has its inputs
            if (spec.side == Side.MERGED) {
                put(ArtifactKey.SERVER, jars.map {
                    it[Side.SERVER] ?: it.getValue(Side.CLIENT)
                }.let(ctx.layout::file))
            }
            // `fetchBothSides` products (reindev) carry the non-env
            // side's dist for consumers that need the full class set —
            // FoxLoader 2.x's unified patcher builds its superset
            // patch input from both dists. Never on the classpath.
            val productId = spec.product.removePrefix("minecraft:")
            if (spec.side != Side.MERGED &&
                    ConversionProvider.PRODUCTS[productId]
                        ?.fetchBothSides == true) {
                val other = if (spec.side == Side.CLIENT)
                    Side.SERVER else Side.CLIENT
                put(other.artifactKey(), jars.map {
                    it.getValue(other)
                }.let(ctx.layout::file))
            }
        }

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    override val mappings: Provider<xyz.wagyourtail.unimined.mapping.MappingTree> =
        ctx.providers.provider { mappingTree("official") }

    // conversion products ship decompiled artifacts — never obf'd
    override val obfuscated: Provider<Boolean> =
        ctx.providers.provider { false }

    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider { NamespaceMode.IDENTITY }

    override val capabilities = ctx.providers.provider {
        EnvironmentCapabilities(
            minecraftVersion = spec.version.version,
            javaVersion = versionJson().javaVersion?.majorVersion ?: 8
        )
    }

    override val libraries: Provider<List<Dependency>> =
        ctx.providers.provider {
            metadata().libraries.mapNotNull {
                xyz.wagyourtail.unimined.api.spec.applyLibraryRules(
                    it, spec.librarySubstitutions, spec.libraryRules,
                    spec.libraryCoordFilters
                )?.let { c -> ctx.dependencyFactory.create(c) }
            } + ConversionProvider.PRODUCTS[
                    spec.product.removePrefix("minecraft:")
                ]?.extraLibraries.orEmpty().map {
                    ctx.dependencyFactory.create(it)
                }
        }

    override val extraDependencies = ctx.objects.fileCollection()

    override val extraCompileSources = ctx.objects.fileCollection()

    override val assets: Provider<AssetIndex> = ctx.providers.provider {
        val ref = metadata().assetIndex
        ConversionAssetIndex(
            id = ref?.id ?: versionJson().assets ?: "legacy",
            file = ctx.layout.file(ctx.providers.provider {
                if (ref == null) {
                    emptyAssetIndex().toFile()
                } else {
                    downloader.fetch(
                        ref.url,
                        "assets/index-${ref.sha1 ?: ref.id ?: "unknown"}.json",
                        ref.sha1?.lowercase(Locale.ROOT)
                    ).toFile()
                }
            })
        )
    }

    private fun versionJson(): VersionJson =
        docJson.decodeFromJsonElement(rootDocument.get())

    private fun metadata() =
        MinecraftMetadataAssembler.assemble(versionJson(), rootDocument.get())

    private fun emptyAssetIndex(): java.nio.file.Path =
        downloader.cacheDir.resolve("assets/empty-index.json").also {
            if (!java.nio.file.Files.exists(it)) {
                it.parent.toFile().mkdirs()
                java.nio.file.Files.writeString(it, "{\"objects\":{}}")
            }
        }

    private class ConversionAssetIndex(
        override val id: String,
        override val file: Provider<RegularFile>
    ) : AssetIndex

}

internal fun Side.artifactKey() = when (this) {
    Side.CLIENT -> ArtifactKey.CLIENT
    Side.SERVER -> ArtifactKey.SERVER
    Side.MERGED -> ArtifactKey.MERGED
}
