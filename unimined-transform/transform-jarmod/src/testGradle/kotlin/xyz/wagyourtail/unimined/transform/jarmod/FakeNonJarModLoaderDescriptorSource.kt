package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource

/**
 * Test-only non-jarmod loader row. The real fabric/forge descriptors
 * live in their own transform modules; depending on those here would
 * create a test-only project cycle because forge/fabric consume parts
 * of the jarmod module. ServiceLoader registration lets wiring tests
 * exercise the production descriptor lookup without that cycle.
 */
class FakeNonJarModLoaderDescriptorSource : LoaderDescriptorSource {
    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "fabric",
            family = "fabric",
            repos = emptyList(),
            loaderCoords = { mc, v -> "example.fabric:fabric-loader:$mc-$v" },
            prodNamespace = "intermediary"
        )
    )
}
