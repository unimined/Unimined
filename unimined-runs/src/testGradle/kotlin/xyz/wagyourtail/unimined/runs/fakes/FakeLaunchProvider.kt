package xyz.wagyourtail.unimined.runs.fakes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.minecraft.spi.*
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource

/**
 * One fixed version document — enough for `VersionData.parse` +
 * `LaunchResolver` on the run-task substitution path.
 */
class FakeLaunchMetadata : LaunchMetadata {

    override val rootId = "test-1.0"

    override fun versionJson(id: String): JsonObject =
        Json.parseToJsonElement(
            """{"id":"$id","type":"release","mainClass":"mc.Main",
               "arguments":{"game":[],"jvm":[]}}"""
        ) as JsonObject

}

/** Test-only ProductArtifacts over fixed providers — identical to the
 *  other modules' fakes except `launch` is populated:
 *  `run<Name>`'s launch-properties substitution reads the document. */
class FakeLaunchArtifacts(private val ctx: ProviderContext) : ProductArtifacts {

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
        EnvironmentCapabilities(minecraftVersion = "1.0")
    }
    override val libraries: Provider<List<Dependency>> =
        ctx.providers.provider { emptyList() }
    override val extraDependencies = ctx.objects.fileCollection()
    override val extraCompileSources = ctx.objects.fileCollection()
    override val assets: Provider<AssetIndex> =
        ctx.providers.provider { null }
    override val launch: LaunchMetadata = FakeLaunchMetadata()
}

/**
 * Name-addressable fake (`provider("fake")`) — `supports() = false`
 * keeps it out of by-supports selection; the override path matches by
 * name without checking `supports`.
 */
class FakeProvider : ProductProvider {

    override val name = "fake"

    override fun supports(spec: ProductSpec) = false

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts =
        FakeLaunchArtifacts(ctx)

}
