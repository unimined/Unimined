package xyz.wagyourtail.unimined.transform.jarmod.internal

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Jarmod-family loader products — `jarmod` (vanilla jar injection +
 * JarModAgent runtime coremodding). The "loader" coordinate is the
 * JarModAgent artifact itself; `modloader` and `fg1`-era forge reuse
 * this family with their own descriptors once layered.
 */
internal class JarModLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "jarmod",
            family = "jarmod",
            mavens = listOf(
                "https://maven.wagyourtail.xyz/releases/",
                "https://maven.wagyourtail.xyz/snapshots/"
            ),
            loaderCoords = { _, v -> "xyz.wagyourtail.unimined:JarModAgent:$v" },
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        )
    )

}
