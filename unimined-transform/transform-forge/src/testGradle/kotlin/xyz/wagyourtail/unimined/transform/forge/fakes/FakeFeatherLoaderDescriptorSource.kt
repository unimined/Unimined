package xyz.wagyourtail.unimined.transform.forge.fakes

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource

/**
 * Non-Forge feather-shaped loader row for cross-loader wiring tests. It has
 * no transform plugin of its own, so tests can apply only the Forge transform
 * and verify that Forge contributions remain inert on this loader.
 */
class FakeFeatherLoaderDescriptorSource : LoaderDescriptorSource {
    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "feathertest",
            family = "fabric",
            repos = emptyList(),
            loaderCoords = { _, version -> "com.example:feathertest-loader:$version" },
            prodNamespace = "intermediary",
            transformPlugin = null
        )
    )
}
