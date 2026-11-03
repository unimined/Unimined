package xyz.wagyourtail.unimined.transform.forge.internal.installer

import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Forge-family loader products — the 1.x `ForgeLike` product subclasses
 * (`MinecraftForge`/`NeoForged`/`Cleanroom`) as data rows. The packaging
 * era (jar-patches/binpatches/userdev — what 1.x split into FG1/FG2/
 * "FG3") is deliberately *not* here: it's probed from the resolved
 * loader artifact, and `net.neoforged` vs `net.minecraftforge` differ
 * only in coordinates/repo/prodNs, not pipeline.
 */
internal class ForgeLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "forge",
            family = "forge",
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(LoaderRepo.Maven("https://maven.minecraftforge.net/")),
            loaderCoords = { mc, v -> "net.minecraftforge:forge:$mc-$v" },
            prodNamespace = "searge",
            capabilities = mapOf("atTool" to "8.2.2"),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                )
            )
        ),
        LoaderDescriptor(
            id = "lexforge",
            family = "forge",
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(LoaderRepo.Maven("https://maven.minecraftforge.net/")),
            loaderCoords = { mc, v -> "net.minecraftforge:forge:$mc-$v" },
            prodNamespace = "searge",
            capabilities = mapOf("atTool" to "8.2.2"),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                )
            )
        ),
        LoaderDescriptor(
            id = "neoforge",
            family = "forge",
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(LoaderRepo.Maven("https://maven.neoforged.net/releases")),
            // 1.x: `forge:$mc-$v` on 1.20.1; later `neoforge:<mc-minus-1.>.$v`
            // appends .0 if mc version isn't 3-length (e.g. 1.20 -> 1.20.0, or 26.3 -> 26.3.0)
            loaderCoords = { mc, v ->
                if (mc == "1.20.1") "net.neoforged:forge:$mc-$v"
                else {
                    var base = if (mc.count { it == '.' } < 2) {
                        "$mc.0"
                    } else {
                        mc
                    }.removePrefix("1.")
                    "net.neoforged:neoforge:$base.$v"
                }
            },
            prodNamespace = "mojmap",
            capabilities = mapOf("atTool" to "8.2.2"),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/neoforge.mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                )
            )
        ),
        LoaderDescriptor(
            id = "cleanroom",
            family = "forge",
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(
                LoaderRepo.Maven("https://repo.cleanroommc.com/releases"),
                LoaderRepo.Maven("https://repo.cleanroommc.com/snapshots"),
                LoaderRepo.Maven("https://maven.arcseekers.com/releases"),
                LoaderRepo.Maven("https://maven.minecraftforge.net/")
            ),
            loaderCoords = { _, v -> "com.cleanroommc:cleanroom:$v" },
            prodNamespace = "searge",
            // cleanroom runs 1.12.2 on Java 21 (1.x runConfig parity)
            javaVersion = 21,
            capabilities = mapOf(
                "atTool" to "8.1.6"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "mcmod.info",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                )
            )
        ),
    )

}
