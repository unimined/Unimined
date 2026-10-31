package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec

/**
 * DSL target for `env.legacydist {}` — declares where the
 * environment's game jar comes from (declared sources only; the
 * `legacydist` provider never fetches an implicit endpoint).
 *
 * ```kotlin
 * unimined.minecraft.main.legacydist {
 *     file("libs/bta-7.3-client.jar")
 *     namespace = "official"
 * }
 * ```
 */
class LegacyDistSpec {

    /** The declared artifact source — one of [file]/[maven]/[url]/[producer]. */
    var source: DeclaredSource? = null
        private set

    /** Production-side namespace the declared jar's contents carry. */
    var namespace: String = "official"

    /** Optional format hint — `"obf"` marks an obfuscated jar that
     *  must ride the raw→dev remap view. */
    var format: String? = null

    /** A local jar/distribution file (project-relative or absolute). */
    fun file(path: Any) {
        source = DeclaredSource.LocalFile(path.toString())
    }

    /** A Maven coordinate resolved from declared repositories. */
    fun maven(notation: String) {
        source = DeclaredSource.Maven(notation)
    }

    /** A URL fetched once into the content cache — [sha256] pins
     *  the content (warned-but-allowed when omitted). */
    fun url(url: String, sha256: String? = null) {
        source = DeclaredSource.Url(url, sha256)
    }

    /** An external producer run in a provider-owned workdir that
     *  must produce [output] — executable, args, JVM args. */
    fun producer(
        executable: String,
        vararg args: String,
        jvmArgs: List<String> = emptyList(),
        output: String
    ) {
        source = DeclaredSource.Producer(
            executable, args.toList(), jvmArgs, output
        )
    }

    /** The immutable spec stamped onto the environment's spec —
     *  `null` when no source was declared. */
    fun declaration(): DeclaredSourceSpec? =
        source?.let { DeclaredSourceSpec(it, namespace, format) }

}
