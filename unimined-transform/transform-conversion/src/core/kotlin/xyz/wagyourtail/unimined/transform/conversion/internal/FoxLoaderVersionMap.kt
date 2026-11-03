package xyz.wagyourtail.unimined.transform.conversion.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fox2Code's loader↔dist compatibility map for ReIndev FoxLoader —
 * the 1.x `FoxLoaderMinecraftTransformer.foxLoaderVersions` seam.
 * The CDN document is `{ "<foxloader version>": "<reindev version>" }`.
 *
 * This object is pure: [parse], [compatible], [default], [resolve],
 * and [compareVersions] operate on an already-fetched table. The
 * Gradle-bound fetch (cached [xyz.wagyourtail.unimined.minecraft.download.Downloader]
 * with TTL + offline fallbacks, resolution-time only — never config)
 * lives in `ConversionEnvironmentWiring`.
 *
 * Applies to `com.fox2code.FoxLoader` (fox2code maven) only — BTA's
 * FoxLoader fork is a different product with no maven artifact and is
 * not covered by this map.
 */
// public despite the `internal` package — the Gradle-bound
// wiring in `src/main` consumes it across the sourceSet boundary
object FoxLoaderVersionMap {

    const val MAP_URL =
        "https://cdn.fox2code.com/maven/foxloader-version-map.json"

    /** Parse the raw JSON document into foxloader→dist pairs. */
    fun parse(text: String): Map<String, String> =
        Json.parseToJsonElement(text).jsonObject.entries
            .associate { (k, v) -> k to v.jsonPrimitive.content }

    /**
     * 1.x dist-version normalization: ReIndev ships versions like
     * `2.4_01` while the map keys on `2.4_1` — strip leading zeroes
     * after the underscore (`provider.version.replaceFirst(Regex("_0*"), "_")`).
     */
    fun normalizeDist(version: String): String =
        version.replaceFirst(Regex("_0*"), "_")

    /** FoxLoader versions compatible with [dist], ascending.
     *  Normalizes BOTH sides — the map itself mixes `2.9` and
     *  `2.9_03`-style values. */
    fun compatible(pairs: Map<String, String>, dist: String): List<String> {
        val d = normalizeDist(dist)
        return pairs.filterValues { normalizeDist(it) == d }.keys
            .sortedWith(::compareVersions)
    }

    /** Newest FoxLoader version compatible with [dist]. */
    fun default(pairs: Map<String, String>, dist: String): String =
        compatible(pairs, dist).lastOrNull()
            ?: throw IllegalArgumentException(
                "no known foxloader compatible with reindev $dist — " +
                        "known pairs: $pairs"
            )

    /**
     * Resolve the requested loader version against the map for [dist]:
     * a blank [loader] selects the newest compatible release; an
     * explicit version must pair with the dist or the build fails
     * listing the valid choices.
     */
    fun resolve(
        pairs: Map<String, String>,
        dist: String,
        loader: String
    ): String {
        if (loader.isBlank()) return default(pairs, dist)
        if (pairs[loader]?.let { normalizeDist(it) == normalizeDist(dist) } == true)
            return loader
        val valid = compatible(pairs, dist)
        throw IllegalArgumentException(
            "foxloader $loader is not compatible with reindev $dist" +
                    if (valid.isEmpty())
                        " — no known foxloader for that dist; " +
                                "known pairs: $pairs"
                    else
                        " — valid for this dist: ${valid.joinToString()}"
        )
    }

    /**
     * Simplified FlexVer compare — alternate digit/non-digit runs,
     * numeric runs compare numerically, a strictly-prefix version
     * sorts earlier. FoxLoader versions are dotted-numeric with
     * occasional suffixes, which this orders correctly.
     */
    fun compareVersions(a: String, b: String): Int {
        val sa = tokenize(a)
        val sb = tokenize(b)
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val x = sa.getOrNull(i) ?: return -1
            val y = sb.getOrNull(i) ?: return 1
            if (x.first && y.first) {
                val c = x.second.toBigInteger()
                    .compareTo(y.second.toBigInteger())
                if (c != 0) return c
            } else if (x.first != y.first) {
                // numeric run outranks a text run at the same slot
                return if (x.first) 1 else -1
            } else {
                val c = x.second.compareTo(y.second)
                if (c != 0) return c
            }
        }
        return 0
    }

    private fun tokenize(v: String): List<Pair<Boolean, String>> =
        Regex("\\d+|\\D+").findAll(v)
            .map { it.value[0].isDigit() to it.value }
            .toList()
}
