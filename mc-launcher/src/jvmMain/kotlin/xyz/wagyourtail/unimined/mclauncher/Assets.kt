package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Asset index → object download, following the Mojang resources layout. */
object Assets {

    const val RESOURCES_BASE = "https://resources.download.minecraft.net"

    private val json = Json { ignoreUnknownKeys = true }

    /** Shared client — pooled connections across a download batch. */
    private val client: HttpClient = HttpClient.newHttpClient()

    /** One index object entry: `objects/<name> -> { hash, size }`. */
    class ObjectEntry(val name: String, val hash: String, val size: Long)

    /** The asset index document (`indexes/<id>.json`). */
    @Serializable
    class AssetIndex(
        val objects: Map<String, AssetObject> = emptyMap(),
        @SerialName("map_to_resources") val mapToResources: Boolean = false,
        val virtual: Boolean = false
    ) {
        @Serializable
        class AssetObject(val hash: String, val size: Long = 0L)
    }

    /** Parse an asset index document into object entries. */
    fun entries(indexJson: String): List<ObjectEntry> =
        json.decodeFromString<AssetIndex>(indexJson).objects
            .map { (name, obj) -> ObjectEntry(name, obj.hash, obj.size) }

    /** The `objects/<hh>/<hash>` layout path for [hash]. */
    fun objectPath(hash: String): Path = Path.of("objects", hash.substring(0, 2), hash)

    /**
     * Ensure every object in [indexJson] exists under [root]. Returns the
     * number of objects actually downloaded. [fetcher] defaults to a plain
     * HTTP GET; tests can inject a fake — it is invoked from multiple
     * worker threads when [parallelism] > 1, so injected fakes must be
     * thread-safe. Existing files are reused without a hash check
     * (hashes are immutable by construction).
     *
     * Downloads run [parallelism]-wide with [maxAttempts] tries per
     * object (jittered backoff between attempts) — a parallel fan-out
     * hits transient resets/429s/short-reads a serial loop rarely sees.
     * A body's size is checked against the index's declared size before
     * it counts. Failures surface as a single [IOException] after all
     * objects settle, carrying the per-object causes as suppressed.
     */
    fun downloadObjects(
        indexJson: String,
        root: Path,
        offline: Boolean = false,
        fetcher: (url: String) -> ByteArray = defaultFetcher,
        parallelism: Int = 16,
        maxAttempts: Int = 4
    ): Int {
        val needed = entries(indexJson)
        val missing = needed.filter { !root.resolve(objectPath(it.hash)).exists() }
        if (missing.isEmpty()) return 0
        if (offline) {
            throw IOException(
                "${missing.size} asset objects missing and offline mode is enabled"
            )
        }
        val workers = parallelism.coerceIn(1, missing.size)
        if (workers == 1) {
            var downloaded = 0
            for (entry in missing) {
                fetchWithRetry(entry, root, fetcher, maxAttempts)
                downloaded++
            }
            return downloaded
        }
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = missing.map { entry ->
                CompletableFuture.supplyAsync(
                    { fetchWithRetry(entry, root, fetcher, maxAttempts) }, pool
                )
            }
            // let every object settle before surfacing the failures —
            // one flaky URL shouldn't strand the ones that would succeed
            val failures = CompletableFuture
                .allOf(*futures.toTypedArray())
                .handle { _, _ ->
                    futures.mapNotNull { f ->
                        runCatching { f.join(); null }.getOrElse { it }
                    }
                }.join()
            if (failures.isNotEmpty()) {
                throw IOException(
                    "${failures.size}/${missing.size} asset objects failed to download"
                ).also { io -> failures.forEach { io.addSuppressed(it) } }
            }
            return missing.size
        } finally {
            pool.shutdown()
        }
    }

    private fun fetchWithRetry(
        entry: ObjectEntry, root: Path,
        fetcher: (url: String) -> ByteArray, maxAttempts: Int
    ) {
        val url = "$RESOURCES_BASE/${entry.hash.substring(0, 2)}/${entry.hash}"
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                val body = fetcher(url)
                if (entry.size > 0 && body.size.toLong() != entry.size) {
                    throw IOException(
                        "short read for ${entry.name}: ${body.size} != ${entry.size} bytes"
                    )
                }
                val target = root.resolve(objectPath(entry.hash))
                target.parent.createDirectories()
                // temp + atomic move — a killed download must not leave a
                // partial object at the hash-addressed path
                val tmp = Files.createTempFile(target.parent, entry.hash, ".tmp")
                try {
                    Files.write(tmp, body)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
                return
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted downloading ${entry.name}", e)
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs(attempt))
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted retrying ${entry.name}", e)
                    }
                }
            }
        }
        throw IOException(
            "failed to download ${entry.name} after $maxAttempts attempts",
            lastError
        )
    }

    /** Jittered exponential backoff: 250ms * 2^(attempt-1) ± 50%. */
    private fun backoffMs(attempt: Int): Long {
        val base = 250L shl (attempt - 1).coerceAtMost(5)
        return base + ThreadLocalRandom.current().nextLong(base + 1)
    }

    /**
     * Whether [indexJson] declares `map_to_resources` — pre-1.6 era games
     * read sounds/music from `<gamedir>/resources`, so the objects have to
     * be materialized there instead of just living under `objects/`.
     */
    fun mapsToResources(indexJson: String): Boolean =
        json.decodeFromString<AssetIndex>(indexJson).mapToResources

    /**
     * Copy every object in [indexJson] from [root]'s `objects/` layout into
     * [resourcesDir] under its index name (e.g. `music/game/calm1.ogg`).
     * Objects already present with the right size are skipped. Returns the
     * number of files written.
     */
    fun copyToResources(indexJson: String, root: Path, resourcesDir: Path): Int {
        val index = json.decodeFromString<AssetIndex>(indexJson)
        val dstRoot = resourcesDir.normalize()
        // independent file copies — parallelStream bounds itself to the
        // common pool; order is irrelevant (paths are disjoint per entry)
        return index.objects.entries.parallelStream().map { (name, obj) ->
            val src = root.resolve(objectPath(obj.hash))
            if (!src.exists()) return@map 0
            // index names are paths like "sound/step/grass1.ogg" — guard
            // against a hostile/poisoned index escaping resourcesDir
            val dst = resourcesDir.resolve(name).normalize()
            if (!dst.startsWith(dstRoot)) return@map 0
            if (dst.exists() && Files.size(dst) == obj.size) return@map 0
            dst.parent.createDirectories()
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            1
        }.reduce(0, Int::plus)
    }

    private val defaultFetcher: (String) -> ByteArray = { url ->
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} downloading $url")
        }
        response.body()
    }

}
