package xyz.wagyourtail.unimined.mclauncher

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetsTest {

    private val hash = "a".repeat(40)

    private fun index(mapToResources: Boolean, name: String = "sound/x.ogg") = """{
      "map_to_resources": $mapToResources,
      "objects": { "$name": { "hash": "$hash", "size": 4 } }
    }"""

    @Test
    fun `mapsToResources reads the index flag`() {
        assertTrue(Assets.mapsToResources(index(true)))
        assertFalse(Assets.mapsToResources(index(false)))
        assertFalse(Assets.mapsToResources("""{ "objects": {} }"""))
    }

    @Test
    fun `copyToResources materializes objects under their index names`() {
        val root = Files.createTempDirectory("assets-root")
        val obj = root.resolve(Assets.objectPath(hash))
        obj.parent.createDirectories()
        Files.write(obj, byteArrayOf(1, 2, 3, 4))

        val resources = Files.createTempDirectory("resources")
        assertEquals(1, Assets.copyToResources(index(true), root, resources))
        assertTrue(resources.resolve("sound/x.ogg").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))

        // second run: same size already present -> nothing copied
        assertEquals(0, Assets.copyToResources(index(true), root, resources))
    }

    @Test
    fun `copyToResources refuses paths escaping the resources dir`() {
        val root = Files.createTempDirectory("assets-root")
        val obj = root.resolve(Assets.objectPath(hash))
        obj.parent.createDirectories()
        Files.write(obj, byteArrayOf(1, 2, 3, 4))

        val resources = Files.createTempDirectory("resources")
        assertEquals(0, Assets.copyToResources(index(true, "../evil.bin"), root, resources))
        assertFalse(resources.parent.resolve("evil.bin").exists())
    }
}
