package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * ServiceLoader discovery + coordinate templates for the forge-family
 * loader products.
 */
class ForgeLoaderDescriptorsTest {

    @Test
    fun `forge products resolve through the service registry`() {
        for (id in listOf("forge", "lexforge", "neoforge", "cleanroom")) {
            val descriptor = LoaderRegistry.resolveBuiltin(id)
            assertNotNull(descriptor, "missing descriptor for $id")
            assertEquals("forge", descriptor!!.family)
        }
    }

    @Test
    fun `forge coordinate templates match 1-x`() {
        // base GAV — era classifiers (:universal/:userdev/@zip) are the
        // profile pipeline's call, not product data
        assertEquals(
            "net.minecraftforge:forge:1.20.1-47.2.0",
            LoaderRegistry.resolveBuiltin("forge")!!.loaderCoords("1.20.1", "47.2.0")
        )
        // 1.x NeoForgedMinecraftTransformer: forge:$mc-$ver on 1.20.1,
        // neoforge:<mc-minus-1.>.$ver afterwards
        assertEquals(
            "net.neoforged:forge:1.20.1-47.1.99",
            LoaderRegistry.resolveBuiltin("neoforge")!!.loaderCoords("1.20.1", "47.1.99")
        )
        assertEquals(
            "net.neoforged:neoforge:21.0.0-beta",
            LoaderRegistry.resolveBuiltin("neoforge")!!.loaderCoords("1.21", "0-beta")
        )
    }

    @Test
    fun `forge products declare FML access-transformer discovery`() {
        val descriptor = LoaderRegistry.resolveBuiltin("neoforge")!!
        assertEquals(
            setOf(
                AccessDiscovery.META_INF_ACCESSTRANSFORMER,
                AccessDiscovery.FMLAT_MANIFEST
            ),
            descriptor.metadata.accessDiscovery
        )
        assertEquals("META-INF/neoforge.mods.toml", descriptor.metadata.fileName)
    }

    @Test
    fun `product capabilities surface atTool versions`() {
        assertEquals("8.2.2", LoaderRegistry.resolveBuiltin("forge")!!.capabilities["atTool"])
        assertEquals("8.1.6", LoaderRegistry.resolveBuiltin("cleanroom")!!.capabilities["atTool"])
    }

}
