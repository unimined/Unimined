package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Fabric-family loader products — the 1.x subclass overrides
 * (`FabricLike`/`Quilt`/`LegacyFabric`/`Babric`/`Ornithe`/`Flint`)
 * expressed as data rows. Real behavioral differences are limited to
 * metadata key paths and coordinates; wiring consumes [family] +
 * fields, never `loaderSpec.name` checks.
 */
internal class FabricLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "fabric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.fabricmc.net/"),
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "intermediary",
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                injectedInterfacesKey = "custom.loom:injected_interfaces",
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "quilt",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.quiltmc.org/repository/release"),
            loaderCoords = { _, v -> "org.quiltmc:quilt-loader:$v" },
            prodNamespace = "intermediary",
            metadata = LoaderMetadataSpec(
                fileName = "quilt.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                injectedInterfacesKey = "custom.quilt_loom.injected_interfaces",
                nestedJarsKey = "quilt_loader.jars"
            )
        ),
        LoaderDescriptor(
            id = "legacyfabric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.legacyfabric.net"),
            // legacyfabric publishes under the upstream GAV on their own maven
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "legacyIntermediary",
            capabilities = mapOf("replaceLwjgl" to "2.9.4+legacyfabric.8"),
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(AccessDiscovery.METADATA_KEY),
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "babric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.glass-launcher.net/babric/"),
            loaderCoords = { _, v -> "babric:fabric-loader:$v" },
            prodNamespace = "babricIntermediary",
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(AccessDiscovery.METADATA_KEY),
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "ornithe",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.ornithemc.net/releases"),
            // 1.x OrnitheFabricMinecraftTransformer inherits legacyfabric's
            // loader() — upstream fabric-loader, not an ornithe artifact
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "calamus",
            // ornithe runs on Java 17
            javaVersion = 17,
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(AccessDiscovery.METADATA_KEY),
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "flint",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            mavens = listOf("https://maven.flintloader.net/releases/"),
            loaderCoords = { _, v -> "net.flintloader:punch:$v" },
            prodNamespace = "intermediary",
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(AccessDiscovery.METADATA_KEY),
                nestedJarsKey = "jars"
            )
        ),
    )

}
