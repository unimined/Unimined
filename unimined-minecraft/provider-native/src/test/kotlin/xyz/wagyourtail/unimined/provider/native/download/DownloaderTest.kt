package xyz.wagyourtail.unimined.provider.native.download

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

class DownloaderTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `download verifies sha1 and reuses cache`() {
        val data = "payload".toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(data).joinToString("") { "%02x".format(it) }
        var calls = 0
        val d = Downloader(dir, fetch = { calls++; data })

        val first = d.fetch("https://x/a.jar", "a.jar", sha1)
        assertArrayEquals(data, java.nio.file.Files.readAllBytes(first))
        assertEquals(1, calls)

        // cached — no re-fetch
        val second = d.fetch("https://x/a.jar", "a.jar", sha1)
        assertEquals(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun `force reload bypasses a valid artifact cache`() {
        var calls = 0
        var response = "first".toByteArray()
        val initial = Downloader(dir, fetch = { calls++; response })
        val cached = initial.fetch("https://x/a.jar", "a.jar")

        response = "second".toByteArray()
        val refresh = Downloader(
            cacheDir = dir,
            refreshCache = true,
            fetch = { calls++; response }
        )
        val refreshed = refresh.fetch("https://x/a.jar", "a.jar")
        assertEquals(cached, refreshed)
        assertEquals("second", java.nio.file.Files.readString(refreshed))
        assertEquals(2, calls)
    }

    @Test
    fun `sha1 mismatch throws`() {
        val d = Downloader(dir, fetch = { "data".toByteArray() })
        assertThrows<java.io.IOException> {
            d.fetch("https://x/a.jar", "a.jar", "deadbeef")
        }
    }

    @Test
    fun `metadata refreshes after ttl`() {
        var now = 10_000L
        var calls = 0
        val d = Downloader(
            cacheDir = dir,
            metadataTtl = Duration.ofMillis(100),
            nowMillis = { now },
            fetch = { calls++; "v$calls".toByteArray() }
        )

        val path = d.fetchMetadata("https://x/meta.json", "metadata/meta.json")
        assertEquals("v1", java.nio.file.Files.readString(path))
        assertEquals(
            "v1", java.nio.file.Files.readString(
                d.fetchMetadata("https://x/meta.json", "metadata/meta.json")
            )
        )
        assertEquals(1, calls)

        now += 101
        assertEquals(
            "v2", java.nio.file.Files.readString(
                d.fetchMetadata("https://x/meta.json", "metadata/meta.json")
            )
        )
        assertEquals(2, calls)
    }

    @Test
    fun `force reload refreshes even fresh metadata`() {
        var calls = 0
        val d1 = Downloader(dir, fetch = { calls++; "old".toByteArray() })
        val old = d1.fetchMetadata("https://x/meta.json", "metadata/meta.json")
        assertEquals("old", java.nio.file.Files.readString(old))

        val d2 = Downloader(
            cacheDir = dir,
            refreshCache = true,
            fetch = { calls++; "new".toByteArray() }
        )
        assertEquals(
            "new",
            java.nio.file.Files.readString(
                d2.fetchMetadata("https://x/meta.json", "metadata/meta.json")
            )
        )
        assertEquals(2, calls)
    }

    @Test
    fun `zero metadata ttl always refreshes`() {
        var calls = 0
        val now = 5_000L
        val d1 = Downloader(
            cacheDir = dir,
            nowMillis = { now },
            fetch = { calls++; "old".toByteArray() }
        )
        d1.fetchMetadata("https://x/meta.json", "metadata/meta.json")

        val d2 = Downloader(
            cacheDir = dir,
            nowMillis = { now },
            fetch = { calls++; "new".toByteArray() }
        )
        val result = d2.fetchMetadata(
            "https://x/meta.json", "metadata/meta.json", Duration.ZERO
        )
        assertEquals("new", java.nio.file.Files.readString(result))
        assertEquals(2, calls)
    }

    @Test
    fun `offline uses stale metadata without fetching`() {
        val cached = Downloader(dir, fetch = { "cached".toByteArray() })
            .fetchMetadata("https://x/meta.json", "metadata/meta.json")
        java.nio.file.Files.setLastModifiedTime(
            cached,
            java.nio.file.attribute.FileTime.fromMillis(1L)
        )
        val offline = Downloader(
            cacheDir = dir,
            offline = true,
            refreshCache = true,
            fetch = { error("offline downloader must not fetch") }
        )

        assertEquals(
            "cached",
            java.nio.file.Files.readString(
                offline.fetchMetadata(
                    "https://x/meta.json", "metadata/meta.json", Duration.ZERO
                )
            )
        )
    }

    @Test
    fun `failed online refresh falls back to stale metadata`() {
        var now = 1_000L
        val cache = Downloader(
            cacheDir = dir,
            nowMillis = { now },
            fetch = { "cached".toByteArray() }
        )
        val path = cache.fetchMetadata("https://x/meta.json", "metadata/meta.json")
        now += Duration.ofDays(2).toMillis()

        var warned = false
        val refresh = Downloader(
            cacheDir = dir,
            nowMillis = { now },
            fetch = { throw java.io.IOException("offline") },
            onRefreshFallback = { stale, _ -> warned = stale == path }
        )
        assertEquals(
            "cached",
            java.nio.file.Files.readString(
                refresh.fetchMetadata(
                    "https://x/meta.json", "metadata/meta.json", Duration.ZERO
                )
            )
        )
        assertTrue(warned)
    }

    @Test
    fun `offline missing metadata reports url`() {
        val d = Downloader(dir, offline = true)
        val error = assertThrows<java.io.IOException> {
            d.fetchMetadata("https://x/meta.json", "metadata/meta.json")
        }
        assertTrue(error.message!!.contains("https://x/meta.json"))
    }

}
