package xyz.wagyourtail.unimined.fakes

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.*

/** Test-only ProductArtifacts over fixed providers — supports any
 *  version so frontend wiring tests can `evaluate()` envs whose
 *  post-DSL hooks resolve the provider spec. */
class FakeArtifacts(private val ctx: ProviderContext) : ProductArtifacts {

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        ctx.providers.provider { emptyList() }

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = mapOf(
        ArtifactKey.CLIENT to ctx.layout.buildDirectory.file("fake/client.jar"),
        ArtifactKey.SERVER to ctx.layout.buildDirectory.file("fake/server.jar"),
        ArtifactKey.MERGED to ctx.layout.buildDirectory.file("fake/merged.jar"),
        ArtifactKey.COMMON to ctx.layout.buildDirectory.file("fake/common.jar"),
    )
    override val mappings: Provider<MappingTree> = ctx.providers.provider {
        mappingTree("official")
    }
    override val namespaceMode: Provider<NamespaceMode> =
        ctx.providers.provider { NamespaceMode.REMAPPABLE }
    override val obfuscated: Provider<Boolean> =
        ctx.providers.provider { false }
    override val capabilities = ctx.providers.provider {
        EnvironmentCapabilities(minecraftVersion = "1.20.1")
    }
    override val libraries: Provider<List<Dependency>> = ctx.providers.provider { emptyList() }
    override val extraDependencies = ctx.objects.fileCollection()
    override val extraCompileSources = ctx.objects.fileCollection()
    override val assets: Provider<AssetIndex> = ctx.providers.provider { null }
    override val launch: LaunchMetadata? = null
}

/**
 * Test-only provider impl, registered through `META-INF/services` on the
 * test classpath. Accepts every spec — wiring tests only need the
 * resolved-artifacts shape, never real product negotiation. Selected by
 * name (`provider.set("fake")`); `priority = -1` sits it below the
 * catch-all `native` (0) so capability probes in tests without an
 * explicit provider never see it as an ambiguity.
 */
class FakeProvider : ProductProvider {

    override val name = "fake"

    override val priority: Int = -1

    override fun supports(spec: ProductSpec) = true

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts {
        calls.add(spec.version.version)
        environments.add(spec.environmentId)
        sides.add(spec.side)
        return FakeArtifacts(ctx)
    }

    companion object {
        val calls = mutableListOf<String>()
        val environments = mutableListOf<String>()
        val sides = mutableListOf<xyz.wagyourtail.unimined.api.spec.Side>()
        fun reset() {
            calls.clear()
            environments.clear()
            sides.clear()
        }
    }

}
