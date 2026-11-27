package xyz.wagyourtail.unimined.transform.jarmod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

class NilDescriptorTest {

    private val descriptor = LoaderRegistry().resolve("nil")!!

    @Test
    fun `nil resolves on the jarmod family with variant wiring`() {
        assertNotNull(descriptor)
        assertEquals("jarmod", descriptor.family)
        assertEquals("nil", descriptor.wiringVariant)
        assertEquals("official", descriptor.prodNamespace)
        // NilLoader discovers jars from the run directory's top-level
        // nilmods/ directory; it has no exploded-classpath dev discovery.
        assertEquals("nilmods", descriptor.capabilities["modsDir"])
        assertEquals(
            "xyz.wagyourtail.unimined.transform.jarmod",
            descriptor.transformPlugin
        )
    }

    @Test
    fun `nil accepts any product`() {
        // nilloader is a plain javaagent — MC-version-agnostic
        assertTrue(descriptor.supportsProduct("minecraft:native"))
        assertTrue(descriptor.supportsProduct("custom:anything"))
    }

    @Test
    fun `loader coords are version-only`() {
        assertEquals(
            "com.unascribed:nilloader:1.3.6",
            descriptor.loaderCoords("1.4.7", "1.3.6")
        )
    }

    @Test
    fun `nilloader resolves from sleeping town only`() {
        assertEquals(1, descriptor.repos.size)
        val repo = descriptor.repos.single()
        assertTrue(repo is xyz.wagyourtail.unimined.transform.loader.LoaderRepo.Maven)
        assertEquals(
            "https://repo.sleeping.town",
            (repo as xyz.wagyourtail.unimined.transform.loader.LoaderRepo.Maven).url
        )
    }
}
