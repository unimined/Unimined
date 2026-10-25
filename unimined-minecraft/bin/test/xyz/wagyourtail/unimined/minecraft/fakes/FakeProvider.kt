package xyz.wagyourtail.unimined.minecraft.fakes

import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext

/** Test-only MinecraftArtifacts over fixed providers. */
class FakeArtifacts(private val ctx: ProviderContext) : MinecraftArtifacts {
    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = mapOf(
        ArtifactKey.MERGED to ctx.layout.buildDirectory.file("fake/merged.jar")
    )
    override val mappings: Provider<MappingTree> = ctx.providers.provider {
        object : MappingTree {
            override val namespaces = listOf("official")
            override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
                require(sourceNamespace == "official" && targetNamespace == "official")
            }
        }
    }
    override val libraries: Provider<List<Dependency>> = ctx.providers.provider { emptyList() }
    override val assets: Provider<AssetIndex> = ctx.providers.provider { null }
    override val launch: LaunchMetadata? = null
}

/**
 * Test-only provider impl, registered through `META-INF/services` on the
 * test classpath. Supports only version `1.0` to exercise capability
 * negotiation.
 */
class FakeProvider : MinecraftProvider {

    override val name = "fake"

    override fun supports(spec: MinecraftSpec) = spec.version.version == "1.0"

    override fun resolve(spec: MinecraftSpec, ctx: ProviderContext): MinecraftArtifacts {
        calls.add(spec.version.version)
        environments.add(spec.environmentId)
        return FakeArtifacts(ctx)
    }

    companion object {
        val calls = mutableListOf<String>()
        val environments = mutableListOf<String>()
        fun reset() {
            calls.clear()
            environments.clear()
        }
    }

}
