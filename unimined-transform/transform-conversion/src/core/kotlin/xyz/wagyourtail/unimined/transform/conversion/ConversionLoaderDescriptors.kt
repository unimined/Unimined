package xyz.wagyourtail.unimined.transform.conversion

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo

/**
 * Total-conversion dist products — the 1.x
 * `AbstractTotalConversionMinecraftProvider` trio expressed as data
 * rows. The dist jar IS the complete Minecraft jar: `prodNamespace`
 * is `official` and these products carry no upstream mapping channel
 * (no remap — their classes already ride the vanilla obf shape).
 * The env's MC artifact comes from the dist's own declared source
 * (maven/URL), so the vanilla-class check loaders perform passes
 * naturally — the jar contains the full vanilla class set.
 */
internal class ConversionLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        // loaders targeting conversion dists — the dists themselves
        // (reindev/bta/nsss) are provider-side `ConversionProduct`
        // rows selected via provider("conversion"), NOT loader
        // products
        LoaderDescriptor(
            id = "foxloader",
            family = "conversion",
            transformPlugin = "xyz.wagyourtail.unimined.transform.conversion",
            repos = listOf(
                LoaderRepo.Maven("https://cdn.fox2code.com/maven"),
                LoaderRepo.Maven("https://api.modrinth.com/maven"),
                LoaderRepo.Maven("https://jitpack.io"),
            ),
            // post-2.9 unified `loader` artifact is the primary
            // coordinate; pre-2.9 (`!canCombine`, non-joined sides)
            // splits into `common` + `<side>` artifacts — the
            // supplemental deps ride `sidedArtifacts`/`invoker`
            // capabilities for the env wiring to expand, same
            // split as 1.x `FoxLoaderMinecraftTransformer.loader()`
            loaderCoords = { _, v -> "com.fox2code.FoxLoader:loader:$v" },
            supportedProducts = setOf("minecraft:reindev"),
            capabilities = mapOf(
                "modsDir" to "mods",
                "invoker" to "com.fox2code:FoxLoaderInvoker:1.1.0",
                "sidedArtifacts" to
                    "com.fox2code:FoxLoader:common,com.fox2code:FoxLoader:<side>",
            ),
            prodNamespace = "official",
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
            )
        ),
        LoaderDescriptor(
            id = "bta-babric",
            family = "conversion",
            transformPlugin = "xyz.wagyourtail.unimined.transform.conversion",
            repos = listOf(
                // Turnip-Labs' toolchain maven — hosts the bta-fork
                // fabric-loader (`*-bta.N` versions)
                LoaderRepo.Maven("https://maven.thesignalumproject.net/infrastructure"),
            ),
            // BTA uses a fabric-loader fork published as
            // `net.fabricmc:fabric-loader:x.y.z-bta.N` (e.g.
            // `0.18.4-bta.11`) — verified resolving on signalum
            // infrastructure maven. The BTA FoxLoader fork has no
            // maven artifact — it ships via GitHub releases
            // (github.com/Better-than-Adventure), an ivy feed the
            // descriptor model doesn't cover yet
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            supportedProducts = setOf("minecraft:bta"),
            // fabric-loader fork — consumes a standard `mods/` dir
            capabilities = mapOf(
                "modsDir" to "mods",
            ),
            prodNamespace = "official",
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
            )
        ),
    )

}
