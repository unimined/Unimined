package xyz.wagyourtail.unimined.transform.forge.internal.installer

import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec
import xyz.wagyourtail.unimined.transform.internal.versionAtLeast

/**
 * Forge-family loader products — the 1.x `ForgeLike` product subclasses
 * (`MinecraftForge`/`NeoForged`/`Cleanroom`) as data rows. The packaging
 * era (jar-patches/binpatches/userdev — what 1.x split into FG1/FG2/
 * "FG3") is deliberately *not* here: it's probed from the resolved
 * loader artifact, and `net.neoforged` vs `net.minecraftforge` differ
 * only in coordinates/repo/prodNs, not pipeline.
 */
internal class ForgeLoaderDescriptors : LoaderDescriptorSource {

    /** MinecraftForge reads `META-INF/jarjar` since 1.18.2-40.1.60
     *  (#8657) and 1.19-41.0.34 (#8715); every later Minecraft version
     *  ships it, every earlier one nests nothing. */
    private val forgeJarInJar: (String, String) -> String? = { mc, v ->
        val forge = v.removePrefix("$mc-")
        val supported = when (mc) {
            "1.18.2" -> versionAtLeast(forge, "40.1.60")
            "1.19" -> versionAtLeast(forge, "41.0.34")
            else -> versionAtLeast(mc, "1.19.1")
        }
        if (supported) "jarjar" else null
    }

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "forge",
            family = "forge",
            // merged-env secondaries resolve through the era probe —
            // bare `loaderCoords` jars don't exist for FG2-era forge
            mergedClasspathSelfManaged = true,
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(
                // FG1-era forge artifacts are pom-less dir listings —
                // `:$side`/`universal@zip` zips exist at this maven's
                // root but no `.pom` does, so module metadata alone
                // can't resolve them
                LoaderRepo.Maven(
                    "https://maven.minecraftforge.net/",
                    metadata = LoaderRepo.MetadataMode.POM_AND_ARTIFACT
                )
            ),
            loaderCoords = { mc, v ->
                // version may arrive mc-prefixed (`26.3-66.1.5`
                // from `latest` resolution) or bare (`47.3.0`)
                if (v.startsWith("$mc-")) "net.minecraftforge:forge:$v"
                else "net.minecraftforge:forge:$mc-$v"
            },
            prodNamespace = "searge",
            capabilities = mapOf(
                "atTool" to "8.2.2",
                // userdev `config.json` run keys beyond client/server —
                // materialized as runs; profiles lacking a key no-op
                "runTypes" to "data,clientData,serverData," +
                        "gameTestServer,junit",
                // FML junit bootstrap reads program args from the
                // args-file in this sysprop (NG isJUnit parity)
                "gametestJunit" to "fml.junit.argsfile",
                // named mods{} groups append `id%%dir` entries
                // to this env var (Loom mods{}/NFRT parity)
                "modGroupsEnv" to "MOD_CLASSES"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                ),
                nestedJarsFormat = forgeJarInJar
            )
        ),
        LoaderDescriptor(
            id = "lexforge",
            family = "forge",
            mergedClasspathSelfManaged = true,
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(
                // same pom-less FG1-era feed as `forge`
                LoaderRepo.Maven(
                    "https://maven.minecraftforge.net/",
                    metadata = LoaderRepo.MetadataMode.POM_AND_ARTIFACT
                )
            ),
            loaderCoords = { mc, v ->
                // version may arrive mc-prefixed (`26.3-66.1.5`
                // from `latest` resolution) or bare (`47.3.0`)
                if (v.startsWith("$mc-")) "net.minecraftforge:forge:$v"
                else "net.minecraftforge:forge:$mc-$v"
            },
            prodNamespace = "searge",
            capabilities = mapOf(
                "atTool" to "8.2.2",
                "runTypes" to "data,clientData,serverData," +
                        "gameTestServer,junit",
                "gametestJunit" to "fml.junit.argsfile",
                // named mods{} groups append `id%%dir` entries
                // to this env var (Loom mods{}/NFRT parity)
                "modGroupsEnv" to "MOD_CLASSES"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                ),
                nestedJarsFormat = forgeJarInJar
            )
        ),
        LoaderDescriptor(
            id = "neoforge",
            family = "forge",
            mergedClasspathSelfManaged = true,
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(LoaderRepo.Maven("https://maven.neoforged.net/releases")),
            // 1.x: `forge:$mc-$v` on 1.20.1; later `neoforge:<mc-minus-1.>.$v`.
            // Pad mc to 3 segments BEFORE stripping `1.` — `1.21`→`21.0`
            // (published `21.0.*`), `26.3`→`26.3.0` (published `26.3.0.*`).
            // 1.x stripped first (`26.3`→`26.3`), but every two-part mc
            // then started with `1.`; the pad-first order is required by
            // the 26.x scheme's real `26.x.0` neoforge series — verified
            // against maven.neoforged.net (`26.1.0.*`, `26.3.0.*`).
            loaderCoords = { mc, v ->
                if (mc == "1.20.1") "net.neoforged:forge:$mc-$v"
                else {
                    val base = if (mc.count { it == '.' } < 2) {
                        "$mc.0"
                    } else {
                        mc
                    }.removePrefix("1.")
                    // published neoforge versions are complete
                    // (`21.1.265`, `26.1.2.126`) — only bare build tails
                    // like `265` need the mc-derived base prepended
                    val full = if (v.startsWith("$base.") ||
                        v.count { it == '.' } >= 2) v else "$base.$v"
                    "net.neoforged:neoforge:$full"
                }
            },
            prodNamespace = "mojmap",
            // 1.20.1 ships `net.neoforged:forge` — FML-era ASM can't
            // parse JDK-25 classes. ≥1.20.2 ships `neoforge` +
            // securejarhandler 3.x — class file 69
            javaVersion = { mc, _ ->
                val m = Regex("^1\\.(\\d+)\\.(\\d+)").find(mc)
                val minor = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val patch = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                if (minor < 20 || (minor == 20 && patch < 2)) 21 else 25
            },
            capabilities = mapOf(
                "atTool" to "8.2.2",
                // neoforge datagen is the dev launch with a data
                // launchTarget — mod hooks GatherDataEvent itself
                "datagenArgs" to "--launchTarget data",
                "runTypes" to "data,clientData,serverData," +
                        "gameTestServer,junit",
                "gametestJunit" to "fml.junit.argsfile",
                // named mods{} groups append `id%%dir` entries
                // to this env var (Loom mods{}/NFRT parity)
                "modGroupsEnv" to "MOD_CLASSES"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "META-INF/neoforge.mods.toml",
                accessDiscovery = setOf(
                    AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                    AccessDiscovery.FMLAT_MANIFEST
                ),
                // every neoforge release (1.20.1's `net.neoforged:forge`
                // included) forked from a JarJar-era Forge
                nestedJarsFormat = { _, _ -> "jarjar" }
            )
        ),
        LoaderDescriptor(
            id = "cleanroom",
            family = "forge",
            mergedClasspathSelfManaged = true,
            transformPlugin = "xyz.wagyourtail.unimined.transform.forge",
            repos = listOf(
                LoaderRepo.Maven("https://repo.cleanroommc.com/releases"),
                LoaderRepo.Maven("https://repo.cleanroommc.com/snapshots"),
                LoaderRepo.Maven("https://maven.arcseekers.com/releases"),
                LoaderRepo.Maven("https://maven.minecraftforge.net/")
            ),
            loaderCoords = { _, v -> "com.cleanroommc:cleanroom:$v" },
            prodNamespace = "searge",
            // published artifacts (incl. 0.5.0-alpha userdev) carry
            // org.gradle.jvm.version=25 — variant check fails lower
            javaVersion = { _, _ -> 25 },
            capabilities = mapOf(
                "atTool" to "8.1.6",
                // Cleanroom's FMLDeobfuscatingRemapper reads TSRG, not
                // forge's SRG — 1.x exported `srg2mcp.tsrg`
                "mcpToSrgFormat" to "tsrg",
                // dev.json only declares client/server today, but a
                // profile may add data/test runs — materialization is
                // key-absence tolerant
                "runTypes" to "data,clientData,serverData," +
                        "gameTestServer,junit",
                "gametestJunit" to "fml.junit.argsfile",
                // named mods{} groups append `id%%dir` entries
                // to this env var (Loom mods{}/NFRT parity)
                "modGroupsEnv" to "MOD_CLASSES"
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
