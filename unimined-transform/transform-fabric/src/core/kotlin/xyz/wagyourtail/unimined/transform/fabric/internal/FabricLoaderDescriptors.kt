package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec
import xyz.wagyourtail.unimined.transform.loader.SplitMergeSpec

/**
 * Fabric-family loader products — the 1.x subclass overrides
 * (`FabricLike`/`Quilt`/`LegacyFabric`/`Babric`/`Ornithe`/`Flint`)
 * expressed as data rows. Real behavioral differences are limited to
 * metadata key paths and coordinates; wiring consumes [family] +
 * fields, never `loaderSpec.name` checks.
 */
internal class FabricLoaderDescriptors : LoaderDescriptorSource {

    /**
     * Split-era obf merge — every fabric-family upstream tiny v2
     * (`intermediary`/`intermediary-upstream`) labels its per-side
     * columns `clientOfficial`/`serverOfficial` and its shared column
     * `intermediary` when it ships for a pre-1.3 version (disjoint
     * obf name sets). Declared on every `intermediaryCoords` row, not
     * just babric's: nothing version-gates `loader("fabric")` on a
     * split-era MC (and `customIntermediaries` can supply split
     * columns directly), so a MERGED env must take the 1.x
     * `FabricMinecraftTransformer.merge` remap-then-merge hop — or
     * fail clearly at intermediary resolution when no split-era
     * artifact is published — instead of silently member-merging
     * unmatched obf names.
     */
    private val splitEraIntermediaryMerge = SplitMergeSpec(
        listOf("clientOfficial", "serverOfficial"),
        "intermediary"
    )

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "fabric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            repos = listOf(LoaderRepo.Maven("https://maven.fabricmc.net/")),
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "intermediary",
            intermediaryCoords = { mc -> "net.fabricmc:intermediary:$mc:v2" },
            splitMerge = splitEraIntermediaryMerge,
            capabilities = mapOf(
                // dev-launch mod classpath groups serialize into
                // this sysprop — fabric-loader #585 (loom.mods
                // parity): groups `::`-separated, files `:`
                "classpathGroups" to "fabric.classPathGroups",
                // loom convention — `fabricApi.configureDataGeneration`:
                // fabric-api's executor boots the datagen entrypoints
                "datagenJvmArgs" to
                    "-Dfabric-api.datagen -Dfabric-api.datagen.output-dir={output} -Dfabric-api.datagen.modid={modid}",
                // junit-driven gametest launches
                "gametestJunit" to "net.fabricmc:fabric-loader-junit:{loader}",
                // client gametests ride the env's auto `<env>Client`
                // run — fabric-client-gametest-api's mixin plugin keys
                // off the system property; {modid} scopes which mod's
                // `fabric-client-gametest` entrypoints run
                "gametestClientJvmArgs" to
                    "-Dfabric.client.gametest " +
                    "-Dfabric.client.gametest.modid={modid}",
                // server gametests ride `<env>Server` — fabric-api's
                // gametest module runs `fabric-gametest` entrypoints +
                // @GameTest methods in-process when the flag is set and
                // writes junit xml to report-file; {reportFile} fills a
                // build/reports path per run
                "gametestServerJvmArgs" to
                    "-Dfabric-api.gametest " +
                    "-Dfabric-api.gametest.report-file={reportFile}",
                // `customGameProvider` — suppress the loader's builtin MC
                // game-provider lookup (total-conversion/`empty-*` envs)
                "skipMcProvider" to "-Dfabric.skipMcProvider"
            ),
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
            repos = listOf(LoaderRepo.Maven("https://maven.quiltmc.org/repository/release")),
            loaderCoords = { _, v -> "org.quiltmc:quilt-loader:$v" },
            prodNamespace = "intermediary",
            // quilt rides fabric intermediary — quilt-mappings is
            // the named layer on top
            intermediaryCoords = { mc -> "net.fabricmc:intermediary:$mc:v2" },
            splitMerge = splitEraIntermediaryMerge,
            capabilities = mapOf(
                // dev-launch mod classpath groups serialize into
                // this sysprop — fabric-loader #585 (loom.mods
                // parity): groups `::`-separated, files `:`
                "classpathGroups" to "fabric.classPathGroups",
                "skipMcProvider" to "-Dloader.skipMcProvider"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "quilt.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                injectedInterfacesKey = "quilt_loom.injected_interfaces",
                nestedJarsKey = "quilt_loader.jars"
            )
        ),
        LoaderDescriptor(
            id = "legacyfabric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            repos = listOf(LoaderRepo.Maven("https://repo.legacyfabric.net/legacyfabric")),
            // legacyfabric publishes under the upstream GAV on their own maven
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "legacyIntermediary",
            // 1.x legacyFabricRevisionTransform: generation-keyed group
            intermediaryCoords = { mc -> "net.legacyfabric:intermediary:$mc:v2" },
            splitMerge = splitEraIntermediaryMerge,
            capabilities = mapOf(
                // dev-launch mod classpath groups serialize into
                // this sysprop — fabric-loader #585 (loom.mods
                // parity): groups `::`-separated, files `:`
                "classpathGroups" to "fabric.classPathGroups",
                "replaceLwjgl" to "2.9.4+legacyfabric.18",
                // 1.x Legacy-Fabric-Intermediary-Version manifest attr
                "manifestAttr.Legacy-Fabric-Intermediary-Version" to "1",
                "skipMcProvider" to "-Dfabric.skipMcProvider"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "babric",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            repos = listOf(
                LoaderRepo.Maven("https://maven.glass-launcher.net/babric/"),
                // `replaceLwjgl` GAV (`org.lwjgl.lwjgl:lwjgl:2.9.4+
                // legacyfabric.8`) is published on legacyfabric's maven
                LoaderRepo.Maven("https://repo.legacyfabric.net/legacyfabric")
            ),
            // 1.x BabricMinecraftTransformer: babric publishes its
            // loader fork under the `babric` group, not `net.fabricmc`
            loaderCoords = { _, v ->
                if ("babric" in v) "babric:fabric-loader:$v"
                else "net.fabricmc:fabric-loader:$v"
            },
            prodNamespace = "babricIntermediary",
            intermediaryCoords = { mc -> "babric:intermediary-upstream:$mc:v2" },
            // babric targets beta only — client/server obf spaces are
            // disjoint; upstream's tiny carries both side columns
            splitMerge = splitEraIntermediaryMerge,
            capabilities = mapOf(
                // dev-launch mod classpath groups serialize into
                // this sysprop — fabric-loader #585 (loom.mods
                // parity): groups `::`-separated, files `:`
                "classpathGroups" to "fabric.classPathGroups",
                "replaceLwjgl" to "2.9.4+legacyfabric.18",
                // b1.7.3 ships argo/** inside the server jar — keep it
                // through the merge library-strip (1.x includeGlobs)
                "mergeIncludeGlobs" to "argo/**",
                "skipMcProvider" to "-Dfabric.skipMcProvider"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                nestedJarsKey = "jars"
            )
        ),
        LoaderDescriptor(
            id = "ornithe",
            family = "fabric",
            transformPlugin = "xyz.wagyourtail.unimined.transform.fabric",
            repos = listOf(
                LoaderRepo.Maven("https://maven.ornithemc.net/releases"),
                // ornithe reuses legacyfabric's patched lwjgl GAV
                // (org.lwjgl:* -> 2.9.4+legacyfabric.8), which ornithe's
                // own maven does not host
                LoaderRepo.Maven("https://repo.legacyfabric.net/legacyfabric"),
            ),
            // 1.x OrnitheFabricMinecraftTransformer inherits legacyfabric's
            // loader() — upstream fabric-loader, not an ornithe artifact
            loaderCoords = { _, v -> "net.fabricmc:fabric-loader:$v" },
            prodNamespace = "calamus",
            // gen/side-aware: the environment's own calamus() channel
            // resolves calamus-intermediary(-genN) with the right
            // -client/-server suffix and namespace relabels
            upstreamMappingChannel = "calamus",
            capabilities = mapOf(
                // dev-launch mod classpath groups serialize into
                // this sysprop — fabric-loader #585 (loom.mods
                // parity): groups `::`-separated, files `:`
                "classpathGroups" to "fabric.classPathGroups",
                // OSL takes care of datagen
                "datagenJvmArgs" to
                        "-Dfabric-api.datagen -Dfabric-api.datagen.output-dir={output} -Dfabric-api.datagen.modid={modid}",
                "replaceLwjgl" to "2.9.4+legacyfabric.18",
                "libraryOverlayMeta" to "https://meta.ornithemc.net",
                "skipMcProvider" to "-Dfabric.skipMcProvider"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "fabric.mod.json",
                accessDiscovery = setOf(
                    AccessDiscovery.METADATA_KEY,
                    AccessDiscovery.METADATA_CLASS_TWEAKERS
                ),
                nestedJarsKey = "jars"
            )
        ),
        // NOTE: the `flint` descriptor was removed — its only artifact
        // host (maven.flintloader.net) is offline, so loader("flint")
        // could never resolve. Reintroduce via LoaderRegistry.register
        // if a mirror appears.
    )

}
