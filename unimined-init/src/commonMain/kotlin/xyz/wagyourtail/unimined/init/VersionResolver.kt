package xyz.wagyourtail.unimined.init

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.io.encoding.Base64

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
    fetch: (String) -> String = ::httpGetString,
    private val timeout: Duration = 15.seconds,
    /**
     * Offline cache dir — resolved payloads persist per-URI; on fetch
     * failure a cached response is used while younger than [cacheTtl]
     * (plan §13: offline → cached manifest).
     */
    cacheDir: String? = null,
    private val cacheTtl: Duration = 24.hours
) {

    private val cacheFile = cacheDir?.let { Io.path(it, "version-resolver.json") }
    private val cache: MutableMap<String, Pair<Long, String>> =
        cacheFile?.takeIf { Io.exists(it) }
            ?.let { f -> parseCache(Io.readText(f)) } ?: mutableMapOf()

    private fun fetch(uri: String): String = try {
        fetchBody(uri).also { body ->
            cache[uri] = epochMillis() to body
            persistCache()
        }
    } catch (e: Exception) {
        val (ts, body) = cache[uri] ?: throw e
        if (epochMillis() - ts > cacheTtl.inWholeMilliseconds) {
            throw IllegalStateException("offline and cache for $uri is stale", e)
        }
        body
    }

    private val fetchBody = fetch

    private fun persistCache() {
        val f = cacheFile ?: return
        Io.writeText(f, serializeCache(cache))
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
            "https://meta.fabricmc.net/v2/versions/$kind/$mc")
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
    private fun latestMaven(metadataUri: String, prefix: String?): String {
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
        private fun httpGetString(url: String): String =
            httpGet(url).decodeToString()

        val PISTON_META =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        val FORGE_MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
        val NEOFORGE_MAVEN =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        val QUILT_MAVEN =
            "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml"
        val UNIMINED_PLUGIN_METADATA =
            "https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml"

        /** `{"release": "x.y.z"}`-style field extraction. */
        internal fun jsonField(json: String, key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1)

        // TSV + base64 payload — no JSON lib in core
        private val b64 = Base64.Default

        internal fun serializeCache(m: Map<String, Pair<Long, String>>) =
            m.entries.joinToString("\n") { (u, v) ->
                "$u\t${v.first}\t${b64.encode(v.second.encodeToByteArray())}"
            }

        internal fun parseCache(text: String): MutableMap<String, Pair<Long, String>> =
            text.lineSequence().filter { it.isNotBlank() }.associate { l ->
                val (u, ts, body) = l.split('\t', limit = 3)
                u to (ts.toLong() to b64.decode(body).decodeToString())
            }.toMutableMap()
    }

}
