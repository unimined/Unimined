package xyz.wagyourtail.unimined.runs.fakes

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource

/**
 * A fabric-family-shaped loader descriptor minus the transform — only
 * the capability channels `RunsWiring` consumes matter here
 * (`classpathGroups`/`modGroupsEnv`); the runs module must not take a
 * transform-fabric test dep for descriptor coverage. No provider side
 * is needed: the asserted task properties never touch launch
 * metadata.
 */
class RunsLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "testfabric",
            family = "fabric",
            repos = emptyList(),
            loaderCoords = { _, v -> "com.example:testfabric-loader:$v" },
            prodNamespace = "intermediary",
            capabilities = mapOf(
                "classpathGroups" to "fabric.classPathGroups",
                "modGroupsEnv" to "MOD_CLASSES",
            ),
        ),
        // forge-family shape: folder staging (`modsDir`) PLUS
        // classpath mod discovery (`modGroupsEnv`) — the wiring must
        // stage `modFolderFiles` only there
        LoaderDescriptor(
            id = "testforge",
            family = "forge",
            repos = emptyList(),
            loaderCoords = { _, v -> "com.example:testforge:$v" },
            prodNamespace = "searge",
            capabilities = mapOf(
                "modGroupsEnv" to "MOD_CLASSES",
                "modsDir" to "mods",
            ),
        ),
        // conversion-family shape: folder staging is the only mod
        // discovery — the default jar + `*Direct` set must stage too
        LoaderDescriptor(
            id = "testconv",
            family = "conversion",
            repos = emptyList(),
            loaderCoords = { _, v -> "com.example:testconv:$v" },
            prodNamespace = "official",
            capabilities = mapOf(
                "modsDir" to "mods",
            ),
        )
    )

}
