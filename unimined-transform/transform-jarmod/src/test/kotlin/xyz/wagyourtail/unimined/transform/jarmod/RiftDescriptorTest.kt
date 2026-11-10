package xyz.wagyourtail.unimined.transform.jarmod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

class RiftDescriptorTest {

    private val descriptor = LoaderRegistry().resolve("rift")!!

    @Test
    fun `rift resolves on the jarmod family with variant wiring`() {
        assertNotNull(descriptor)
        assertEquals("jarmod", descriptor.family)
        assertEquals("rift", descriptor.wiringVariant)
        assertEquals("official", descriptor.prodNamespace)
        assertEquals("riftmod.json", descriptor.metadata.fileName)
        assertEquals(
            "xyz.wagyourtail.unimined.transform.jarmod",
            descriptor.transformPlugin
        )
    }

    @Test
    fun `loader coords are MC-keyed when version is empty`() {
        assertEquals(
            "org.dimdev:rift:1.13.2",
            descriptor.loaderCoords("1.13.2", "")
        )
    }

    @Test
    fun `explicit loader version wins over MC version`() {
        assertEquals(
            "org.dimdev:rift:1.13.2",
            descriptor.loaderCoords("1.13", "1.13.2")
        )
    }
}
