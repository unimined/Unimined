package xyz.wagyourtail.unimined.minecraft.download

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.util.writeAtomically

/**
 * Downloads immutable artifacts and mutable metadata into a shared cache.
 * Pure JVM — the Gradle side supplies the cache root and offline setting.
 */
class Downloader(
    val cacheDir: Path,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val offline: Boolean = false,
    private val refreshCache: Boolean = false,
    private val metadataTtl: Duration = Duration.ofHours(24),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> ByteArray = { url ->
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} downloading $url")
        }
        response.body()
    },
    private val onRefreshFallback: (Path, Exception) -> Unit = { path, error ->
        System.getLogger(Downloader::class.java.name).log(
            System.Logger.Level.WARNING,
            "Cache refresh failed; using cached entry {0}: {1}",
            path,
            error.message
        )
    }
) {

    init {
        require(!metadataTtl.isNegative) { "metadata TTL must not be negative" }
    }

    /**
     * Fetch an immutable artifact; a valid cached file is reused indefinitely
     * unless [refreshCache] is set. Offline mode always takes precedence.
     */
    /**
     * Fetch an immutable artifact; a valid cached file is reused indefinitely
     * unless [refreshCache] is set. Offline mode always takes precedence.
     * [sha1] carries the expected hex digest — the algorithm is inferred
     * from length (32=MD5, 40=SHA-1, 64=SHA-256, 128=SHA-512); upstreams
     * publish different digests per endpoint (paper's fill api is sha256).
     */
    fun fetch(url: String, name: String, sha1: String? = null): Path {
        val dest = cachePath(name)
        val cachedValid = dest.exists() && (sha1 == null || digest(dest, sha1) == sha1)
        if (cachedValid && (offline || !refreshCache)) return dest
        if (offline) throw missingCache(url, dest)

        return try {
            val bytes = fetch(url)
            if (sha1 != null && digest(bytes, sha1) != sha1) {
                throw IOException("checksum mismatch for $url — expected $sha1")
            }
            writeAtomically(dest, bytes)
            dest
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            if (!cachedValid) throw e
            onRefreshFallback(dest, e)
            dest
        }
    }

    /**
     * Fetch mutable metadata with a TTL. Offline mode never makes a request
     * and accepts stale cache entries. Failed online refreshes fall back to
     * stale data and invoke [onRefreshFallback].
     */
    fun fetchMetadata(url: String, name: String, ttl: Duration = metadataTtl): Path {
        require(!ttl.isNegative) { "metadata TTL must not be negative" }
        val dest = cachePath(name)
        if (dest.exists() && (offline || (!refreshCache && isFresh(dest, ttl)))) return dest
        if (offline) {
            if (dest.exists()) return dest
            throw missingCache(url, dest)
        }

        return try {
            writeAtomically(dest, fetch(url))
            dest
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            if (!dest.exists()) throw e
            onRefreshFallback(dest, e)
            dest
        }
    }

    private fun cachePath(name: String): Path {
        require(name.isNotBlank()) { "cache entry name must not be blank" }
        val root = cacheDir.toAbsolutePath().normalize()
        val path = root.resolve(name).normalize()
        require(path.startsWith(root)) { "cache entry escapes cache root: $name" }
        path.parent.createDirectories()
        return path
    }

    private fun isFresh(path: Path, ttl: Duration): Boolean {
        val modified = Files.getLastModifiedTime(path).toMillis()
        val age = (nowMillis() - modified).coerceAtLeast(0)
        return age < ttl.toMillis()
    }

    private fun missingCache(url: String, dest: Path) = IOException(
        "offline mode: no valid cached artifact for $url (expected at $dest); " +
                "resolve this Minecraft version once while online"
    )

    private fun writeAtomically(dest: Path, bytes: ByteArray) {
        dest.parent.createDirectories()
        // shared helper owns the temp+move; the mtime stamp is
        // downloader-specific (cache freshness checks read it)
        dest.writeAtomically { Files.write(it, bytes) }
        Files.setLastModifiedTime(dest, java.nio.file.attribute.FileTime.fromMillis(nowMillis()))
    }

    /** Algorithm for [expected] by hex length; defaults to SHA-1. */
    private fun algorithm(expected: String?): String = when (expected?.length) {
        32 -> "MD5"
        64 -> "SHA-256"
        128 -> "SHA-512"
        else -> "SHA-1"
    }

    private fun digest(path: Path, expected: String?): String =
        path.inputStream().use { input ->
            MessageDigest.getInstance(algorithm(expected)).run {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
                digest().toHex()
            }
        }

    private fun digest(bytes: ByteArray, expected: String?): String =
        MessageDigest.getInstance(algorithm(expected)).digest(bytes).toHex()

}
