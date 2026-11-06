package xyz.wagyourtail.unimined.transform.forge.fakes

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.*

/** Test-only ProductArtifacts over fixed providers. */
class FakeArtifacts(private val ctx: ProviderContext, mcVersion: String) : ProductArtifacts {

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    // mirrors NativeProvider's era rule without a manifest to probe:
    // Mojang jars are obf'd until the 26.x unobf'd era
    override val obfuscated: Provider<Boolean> =
        ctx.providers.provider {
            xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
                .compare(mcVersion, "26.0") < 0
        }

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = mapOf(
        ArtifactKey.MERGED to ctx.layout.buildDirectory.file("fake/merged.jar")
    )

    override val auxiliaryArtifacts: Map<String, Provider<RegularFile>> = mapOf(
        "client_mappings" to ctx.layout.buildDirectory.file("fake/mappings.tiny")
    )

    override val mappings: Provider<MappingTree> = ctx.providers.provider {
        mappingTree("official")
    }

    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider { NamespaceMode.REMAPPABLE }

    override val capabilities = ctx.providers.provider {
        EnvironmentCapabilities(minecraftVersion = "1.0")
    }

    override val completedStages: Map<ArtifactKey, Provider<ArtifactStateLog>> =
        emptyMap()

    override val libraries: Provider<List<Dependency>> = ctx.providers.provider { emptyList() }
    override val extraDependencies = ctx.objects.fileCollection()
    override val extraCompileSources = ctx.objects.fileCollection()
    override val assets: Provider<AssetIndex> = ctx.providers.provider { null }
    override val launch: LaunchMetadata? = null
}

/** Test-only provider impl registered through `META-INF/services` on the
 *  test classpath under the `native` name — `resolved()` selects the
 *  default provider, and wiring tests exercise the selection path
 *  without needing real Minecraft artifacts. */
class FakeProvider : ProductProvider {

    override val name = "native"

    override fun supports(spec: ProductSpec) = true

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts =
        FakeArtifacts(ctx, spec.version.version)
}

/** Same fake under the `fake` name for tests that pin a provider
 *  explicitly (mirrors unimined-minecraft's testGradle fake). */
class FakeNamedProvider : ProductProvider {

    override val name = "fake"

    override fun supports(spec: ProductSpec) = false

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts =
        FakeArtifacts(ctx, spec.version.version)
}
