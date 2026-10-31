package xyz.wagyourtail.unimined.transform.jarmod.internal

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Jarmod-family loader products — `jarmod` (vanilla jar injection +
 * JarModAgent runtime coremodding). The "loader" coordinate is the
 * JarModAgent artifact itself. ModLoader isn't a loader — it's a
 * `jarMod` dep patched by `jarmod-modloader-fixes` (exists-gated
 * on `ModLoader.class`); `fg1` is FML-era forge on the same family.
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
            loaderCoords = { _, v -> "xyz.wagyourtail.unimined:jarmod-agent:$v:all" },
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        ),
        LoaderDescriptor(
            id = "fg1",
            family = "jarmod",
            mavens = listOf(
                "https://maven.wagyourtail.xyz/releases/",
                "https://maven.wagyourtail.xyz/snapshots/"
            ),
            loaderCoords = { _, v -> "xyz.wagyourtail.unimined:jarmod-agent:$v:all" },
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        ),
        // 1.x LiteLoaderMinecraftTransformer extended the jarmod
        // transformer — jarmod-family wiring with its own artifact.
        // dl.liteloader.com is an artifact-only repo; sponge for its
        // mixin/lib deps.
        LoaderDescriptor(
            id = "liteloader",
            family = "jarmod",
            mavens = listOf(
                "https://dl.liteloader.com/versions/",
                "https://repo.spongepowered.org/maven/"
            ),
            loaderCoords = { _, v -> "com.mumfrey:liteloader:$v" },
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        )
    )
}
