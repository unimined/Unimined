package xyz.wagyourtail.unimined.mclauncher

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `downloadObjects fetches missing objects in parallel`() {
        val root = Files.createTempDirectory("assets-root")
        val objects = (1..8).associate { i ->
            "sound/$i.ogg" to Pair("b".repeat(39) + i, byteArrayOf(1, i.toByte()))
        }
        val indexJson = """{"objects":{${
            objects.entries.joinToString(",") { (name, v) ->
                "\"$name\":{\"hash\":\"${v.first}\",\"size\":${v.second.size}}"
            }
        }}}"""
        val fetched = ConcurrentLinkedQueue<String>()
        val count = Assets.downloadObjects(indexJson, root, parallelism = 4, fetcher = { url ->
            fetched += url
            val h = url.substringAfterLast('/')
            objects.values.first { it.first == h }.second
        })
        assertEquals(8, count)
        assertEquals(8, fetched.size)
        objects.values.forEach { (h, body) ->
            assertTrue(root.resolve(Assets.objectPath(h)).readBytes().contentEquals(body))
        }
        // idempotent — all present now
        assertEquals(0, Assets.downloadObjects(indexJson, root, fetcher = { error("no fetch expected") }))
    }

    @Test
    fun `downloadObjects retries transient failures then succeeds`() {
        val root = Files.createTempDirectory("assets-root")
        val attempts = AtomicInteger()
        assertEquals(1, Assets.downloadObjects(index(false), root, maxAttempts = 4, fetcher = {
            if (attempts.incrementAndGet() < 3) throw IOException("flaky")
            byteArrayOf(1, 2, 3, 4)
        }))
        assertEquals(3, attempts.get())
        assertTrue(root.resolve(Assets.objectPath(hash)).exists())
    }

    @Test
    fun `downloadObjects fails after maxAttempts and leaves no partial file`() {
        val root = Files.createTempDirectory("assets-root")
        val attempts = AtomicInteger()
        assertFailsWith<IOException> {
            Assets.downloadObjects(index(false), root, maxAttempts = 2, fetcher = {
                attempts.incrementAndGet()
                throw IOException("always down")
            })
        }
        assertEquals(2, attempts.get())
        assertFalse(root.resolve(Assets.objectPath(hash)).exists())
        // no temp litter either
        val tmp = root.resolve(Assets.objectPath(hash)).parent
        assertFalse(tmp.exists() && Files.list(tmp).anyMatch { it.fileName.toString().endsWith(".tmp") })
    }

    @Test
    fun `downloadObjects rejects short reads against the declared size`() {
        val root = Files.createTempDirectory("assets-root")
        assertFailsWith<IOException> {
            Assets.downloadObjects(index(false), root, maxAttempts = 1, fetcher = {
                byteArrayOf(1, 2) // index says 4 bytes
            })
        }
        assertFalse(root.resolve(Assets.objectPath(hash)).exists())
    }

    @Test
    fun `downloadObjects respects offline mode`() {
        val root = Files.createTempDirectory("assets-root")
        assertFailsWith<IOException> {
            Assets.downloadObjects(index(false), root, offline = true, fetcher = { error("no fetch expected") })
        }
    }
}
