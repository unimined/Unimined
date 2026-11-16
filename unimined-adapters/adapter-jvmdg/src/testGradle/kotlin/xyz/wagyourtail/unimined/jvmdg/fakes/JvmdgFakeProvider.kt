package xyz.wagyourtail.unimined.jvmdg.fakes

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.*

/** Test-only ProductArtifacts over fixed providers — capability
 *  javaVersion is always 8 (the era floor jvmdg tests exercise). */
private class FakeArtifacts(private val ctx: ProviderContext) : ProductArtifacts {

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = mapOf(
        ArtifactKey.MERGED to ctx.layout.buildDirectory.file("fake/merged.jar")
    )
    override val mappings: Provider<MappingTree> = ctx.providers.provider {
        mappingTree("official")
    }
    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider { NamespaceMode.REMAPPABLE }
    override val obfuscated: Provider<Boolean> =
        ctx.providers.provider { false }
    override val capabilities = ctx.providers.provider {
        EnvironmentCapabilities(
            minecraftVersion = "test", javaVersion = 8)
    }
    override val libraries: Provider<List<Dependency>> = ctx.providers.provider { emptyList() }
    override val extraDependencies = ctx.objects.fileCollection()
    override val extraCompileSources = ctx.objects.fileCollection()
    override val assets: Provider<AssetIndex> = ctx.providers.provider { null }
    override val launch: LaunchMetadata? = null
}

/**
 * Test-only provider impl, registered through `META-INF/services` on
 * the test classpath. Supports every version so wiring tests can pick
 * era ids freely; always reports `javaVersion = 8` capabilities.
 */
class JvmdgFakeProvider : ProductProvider {

    override val name = "fake"

    override fun supports(spec: ProductSpec) = true

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts =
        FakeArtifacts(ctx)

}
