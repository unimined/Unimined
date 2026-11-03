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

    /** All MC release versions, newest-first (piston-meta). */
    fun minecraftVersions(): List<String> = runCatching {
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(fetch(PISTON_META)).map { it.groupValues[1] }.toList()
    }.getOrElse { Probed.expand("@mc") }

    /** MCP channel versions for [mc] — `stable_N`/`snapshot_N`
     *  (forge files index), newest-first. */
    fun mcpVersions(mc: String): List<String> = runCatching {
        val json = fetch(
            "https://files.minecraftforge.net/maven/de/oceanlabs/mcp/versions.json")
        val m = Regex("\"$mc\"\\s*:\\s*\\{([^}]+)\\}").find(json)
            ?: return emptyList()
        val (snap, stable) = listOf("snapshot", "stable").map { ch ->
            Regex("\"$ch\"\\s*:\\s*\\[([^]]*)\\]").find(m.groupValues[1])
                ?.groupValues?.get(1)?.let {
                    Regex("[0-9]+").findAll(it)
                        .map { v -> "${ch}_${v.groupValues[0]}" }.toList()
                } ?: emptyList()
        }
        stable + snap
    }.getOrElse { Probed.expand("mcp:$mc") }

    /** Latest MC release version (piston-meta `latest.release`). */
    fun latestMinecraft(): String = runCatching {
        jsonField(fetch(PISTON_META), "release")
            ?: error("no release id in piston-meta manifest")
    }.getOrElse { baked("@mc") ?: throw it }


    /** Build-time probed cache — offline floor after the disk cache. */
    private fun baked(key: String): String? =
        Probed.expand(key).firstOrNull()?.takeIf { it.isNotEmpty() }

    /** Newest-first supported versions for [loader] — live list, or
     *  the baked cache when offline. Empty when nothing resolves. */
    fun loaderVersions(loader: String, mc: String): List<String> =
        runCatching { liveLoaderVersions(loader, mc) }
            .getOrElse { Probed.expand("$loader:$mc")
                .ifEmpty { Probed.expand(loader) } }

    private fun liveLoaderVersions(loader: String, mc: String): List<String> =
        when (loader.lowercase()) {
            "fabric" -> fabricMetaVersions("loader", mc, "loader")
            "yarn" -> fabricMetaFlat("yarn", mc)
            "forge" -> mavenVersions(FORGE_MAVEN, mc)
            "neoforge" -> mavenVersions(NEOFORGE_MAVEN, null)
            "quilt" -> mavenVersions(QUILT_MAVEN, null)
            "babric" -> mavenVersions(BABRIC_MAVEN, null)
            "foxloader" -> mavenVersions(FOXLOADER_MAVEN, null)
            "stationapi" -> mavenVersions(STATION_MAVEN, null)
            "osl" -> mavenVersions(OSL_MAVEN, null)
            "bta" -> mavenVersions(BTA_MAVEN, null)
            "reindev" -> mavenVersions(REINDEV_MAVEN, null)
            else -> error("no version endpoint known for loader '$loader'")
        }

    /** Resolve a version [spec] for [axis]: `latest`, a trailing-`*`
     *  prefix (`1.20.*`, `0.16.*`), or an exact string — checked
     *  against the live-then-baked version list. */
    fun resolveVersion(axis: String, spec: String): String {
        if (spec == "latest") return loaderVersions(axis, "").firstOrNull()
            ?: error("no versions known for '$axis'")
        if (spec.endsWith("*")) {
            val p = spec.dropLast(1)
            return loaderVersions(axis, "").firstOrNull { it.startsWith(p) }
                ?: error("no version matching '$spec' for '$axis'")
        }
        return spec
    }

    /** Latest loader version for [loader] ([mc] scopes fabric/quilt). */
    fun latestLoader(loader: String, mc: String): String =
        loaderVersions(loader, mc).firstOrNull()
            ?: error("no version endpoint known for loader '$loader'")

    /** Latest published unimined plugin version. */
    fun latestPlugin(): String = runCatching {
        mavenVersions(UNIMINED_PLUGIN_METADATA, null).firstOrNull()
            ?: error("no versions in $UNIMINED_PLUGIN_METADATA")
    }.getOrElse { baked("@plugin") ?: throw it }

    /** All `<key>.version` entries — fabric-meta order (newest-first). */
    private fun fabricMetaVersions(kind: String, mc: String, key: String): List<String> {
        val json = fetch(
            "https://meta.fabricmc.net/v2/versions/$kind/$mc")
        return Regex("\"$key\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json).map { it.groupValues[1] }.toList()
    }

    /** Flat fabric-meta payloads (yarn entries have top-level `version`). */
    private fun fabricMetaFlat(kind: String, mc: String): List<String> {
        val json = fetch(
            "https://meta.fabricmc.net/v2/versions/$kind/$mc")
        return Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json).map { it.groupValues[1] }.toList()
    }

    /**
     * `maven-metadata.xml` newest-first versions matching [prefix]
     * (e.g. forge's `"1.20.1-"` scope).
     */
    private fun mavenVersions(metadataUri: String, prefix: String?): List<String> =
        mavenVersions0(fetch(metadataUri), prefix)

    private fun mavenVersions0(xml: String, prefix: String?): List<String> {
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(xml).map { it.groupValues[1] }.toList().reversed()
        return prefix?.let { p -> versions.filter { it.startsWith(p) } }
            ?: versions
    }

    companion object {
        private fun httpGetString(url: String): String =
            httpGet(url).decodeToString()

        val PISTON_META =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        val BABRIC_MAVEN =
            "https://maven.glass-launcher.net/releases/babric/fabric-loader/maven-metadata.xml"
        val FOXLOADER_MAVEN =
            "https://cdn.fox2code.com/maven/com/fox2code/FoxLoader/loader/maven-metadata.xml"
        val STATION_MAVEN =
            "https://maven.glass-launcher.net/releases/net/modificationstation/StationAPI/maven-metadata.xml"
        val OSL_MAVEN =
            "https://maven.ornithemc.net/releases/net/ornithemc/osl/maven-metadata.xml"
        val BTA_MAVEN =
            "https://maven.glass-launcher.net/releases/com/github/bta/bta/maven-metadata.xml"
        val REINDEV_MAVEN =
            "https://maven.glass-launcher.net/releases/com/github/reindev/reindev/maven-metadata.xml"
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
