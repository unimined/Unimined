package xyz.wagyourtail.unimined.init

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Resolves `latest` for plugin/MC/loader/mappings versions through
 * the same manifest/metadata endpoints the providers use (plan §13 —
 * never a hardcoded version table). Parsing is split from fetching so
 * tests exercise real payload shapes without network.
 *
 * Endpoints: piston-meta version manifest (MC), fabric-meta
 * (loader/yarn), loader Maven `maven-metadata.xml` (forge/neoforge/
 * quilt), wagyourtail maven (the unimined plugin marker).
 */
class VersionResolver(
    fetch: (URI) -> String = ::httpGet,
    private val timeout: Duration = Duration.ofSeconds(15),
    /**
     * Offline cache dir — resolved payloads persist per-URI; on fetch
     * failure a cached response is used while younger than [cacheTtl]
     * (plan §13: offline → cached manifest).
     */
    cacheDir: java.nio.file.Path? = null,
    private val cacheTtl: Duration = Duration.ofHours(24)
) {

    private val cacheFile = cacheDir?.resolve("version-resolver.json")
    private val cache: MutableMap<String, Pair<Long, String>> =
        cacheFile?.takeIf { java.nio.file.Files.isRegularFile(it) }
            ?.let { parseCache(it.toFile().readText()) } ?: mutableMapOf()

    private fun fetch(uri: URI): String = try {
        fetchBody(uri).also { body ->
            cache[uri.toString()] = System.currentTimeMillis() to body
            persistCache()
        }
    } catch (e: Exception) {
        val (ts, body) = cache[uri.toString()]
            ?: throw e
        if (System.currentTimeMillis() - ts > cacheTtl.toMillis()) {
            throw IllegalStateException("offline and cache for $uri is stale", e)
        }
        body
    }

    private val fetchBody = fetch

    private fun persistCache() {
        val f = cacheFile ?: return
        f.parent?.let { java.nio.file.Files.createDirectories(it) }
        f.toFile().writeText(serializeCache(cache))
    }

    /** Latest MC release version (piston-meta `latest.release`). */
    fun latestMinecraft(): String {
        val json = fetch(PISTON_META)
        return jsonField(json, "release")
            ?: error("no release id in piston-meta manifest")
    }

    /** Latest loader version for [loader] ([mc] scopes fabric/quilt). */
    fun latestLoader(loader: String, mc: String): String = when (loader.lowercase()) {
        "fabric" -> fabricMeta("loader", mc, "loader")
        "yarn" -> fabricMeta("yarn", mc, "yarn")
        "forge" -> latestMaven(FORGE_MAVEN, mc)
        "neoforge" -> latestMaven(NEOFORGE_MAVEN, null)
        "quilt" -> latestMaven(QUILT_MAVEN, null)
        else -> error("no version endpoint known for loader '$loader'")
    }

    /** Latest published unimined plugin version. */
    fun latestPlugin(): String =
        latestMaven(UNIMINED_PLUGIN_METADATA, null)

    /** fabric-meta `v2/versions/<kind>/<mc>` → first `<kind>.version`. */
    private fun fabricMeta(kind: String, mc: String, key: String): String {
        val json = fetch(
            URI.create("https://meta.fabricmc.net/v2/versions/$kind/$mc")
        )
        // entries look like {"loader": {"version": "x.y.z", ...}} —
        // version may not be the first field in the flat object
        val m = Regex("\"$key\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"")
            .find(json)
        return m?.groupValues?.get(1)
            ?: error("no $kind version for mc $mc in fabric-meta")
    }

    /**
     * `maven-metadata.xml` latest matching [prefix] (e.g. forge's
     * `"1.20.1-"` scoped versions) or the file's `<latest>` otherwise.
     */
    private fun latestMaven(metadataUri: URI, prefix: String?): String {
        val xml = fetch(metadataUri)
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(xml).map { it.groupValues[1] }.toList()
        val candidates = prefix?.let { p ->
            versions.filter { it.startsWith(p) }
        } ?: versions
        return candidates.lastOrNull()
            ?: error("no versions in $metadataUri matching '$prefix'")
    }

    companion object {
        val PISTON_META =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val FORGE_MAVEN =
            URI.create("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
        val NEOFORGE_MAVEN =
            URI.create("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        val QUILT_MAVEN =
            URI.create("https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml")
        val UNIMINED_PLUGIN_METADATA =
            URI.create("https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml")

        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        private fun httpGet(uri: URI): String {
            val req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15)).GET().build()
            return client.send(req, HttpResponse.BodyHandlers.ofString()).body()
        }

        /** `{"release": "x.y.z"}`-style field extraction. */
        internal fun jsonField(json: String, key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1)

        // TSV + base64 payload — no JSON lib in core
        private val b64 = java.util.Base64.getEncoder()
        private val b64d = java.util.Base64.getDecoder()

        internal fun serializeCache(m: Map<String, Pair<Long, String>>) =
            m.entries.joinToString("\n") { (u, v) ->
                "$u\t${v.first}\t${b64.encodeToString(v.second.toByteArray())}"
            }

        internal fun parseCache(text: String) =
            text.lineSequence().filter { it.isNotBlank() }.associate { l ->
                val (u, ts, body) = l.split('\t', limit = 3)
                u to (ts.toLong() to String(b64d.decode(body)))
            }.toMutableMap()
    }

}
