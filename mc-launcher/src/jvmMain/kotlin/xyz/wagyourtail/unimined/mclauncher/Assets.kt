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
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Asset index → object download, following the Mojang resources layout. */
object Assets {

    const val RESOURCES_BASE = "https://resources.download.minecraft.net"

    private val json = Json { ignoreUnknownKeys = true }

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
     * HTTP GET; tests can inject a fake. Existing files are reused without
     * a hash check (hashes are immutable by construction).
     */
    fun downloadObjects(
        indexJson: String,
        root: Path,
        offline: Boolean = false,
        fetcher: (url: String) -> ByteArray = defaultFetcher
    ): Int {
        val needed = entries(indexJson)
        var downloaded = 0
        val missing = needed.filter { !root.resolve(objectPath(it.hash)).exists() }
        if (missing.isEmpty()) return 0
        if (offline) {
            throw IOException(
                "${missing.size} asset objects missing and offline mode is enabled"
            )
        }
        for (entry in missing) {
            val target = root.resolve(objectPath(entry.hash))
            target.parent.createDirectories()
            val body = fetcher("$RESOURCES_BASE/${entry.hash.substring(0, 2)}/${entry.hash}")
            Files.write(target, body)
            downloaded++
        }
        return downloaded
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
        var copied = 0
        for ((name, obj) in index.objects) {
            val src = root.resolve(objectPath(obj.hash))
            if (!src.exists()) continue
            // index names are paths like "sound/step/grass1.ogg" — guard
            // against a hostile/poisoned index escaping resourcesDir
            val dst = resourcesDir.resolve(name).normalize()
            if (!dst.startsWith(resourcesDir.normalize())) continue
            if (dst.exists() && Files.size(dst) == obj.size) continue
            dst.parent.createDirectories()
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            copied++
        }
        return copied
    }

    private val defaultFetcher: (String) -> ByteArray = { url ->
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} downloading $url")
        }
        response.body()
    }

}
