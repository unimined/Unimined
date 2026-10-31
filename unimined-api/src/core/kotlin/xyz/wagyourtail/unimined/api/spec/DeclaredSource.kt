package xyz.wagyourtail.unimined.api.spec

import java.io.Serializable

/**
 * A user-declared game-artifact source for `provider("legacydist")` —
 * declared sources only, never an implicit download endpoint
 * (plan §1100). Carries enough information for a provider to
 * materialize the artifact without consulting any manifest.
 */
sealed interface DeclaredSource : Serializable {

    /** A local jar/distribution file path (project-relative or absolute). */
    data class LocalFile(val path: String) : DeclaredSource

    /** A Maven coordinate resolved from the project's declared
     *  repositories — GAV, optionally with classifier/extension. */
    data class Maven(val notation: String) : DeclaredSource

    /** A URL fetched once into the content cache. [sha256] pins the
     *  content; when absent the artifact is cached by URL hash and a
     *  warning is emitted (declared-source honesty prefers a pin). */
    data class Url(val url: String, val sha256: String? = null) : DeclaredSource

    /** An external producer (builder/downloader) — an executable or
     *  JVM main class invocation run in a provider-owned workdir that
     *  must produce [output]. */
    data class Producer(
        val executable: String,
        val args: List<String> = emptyList(),
        val jvmArgs: List<String> = emptyList(),
        val output: String
    ) : DeclaredSource
}

/**
 * The declared-source spec stamped onto an environment's
 * [MinecraftSpec] — the artifact's source plus the user-authored
 * `namespace`/`format` the resolved artifact reports (the state-log
 * contract real providers report — plan §1140; no real provider ran,
 * so the declaration *is* the report).
 */
data class DeclaredSourceSpec(
    val source: DeclaredSource,
    /** Production-side namespace of the jar's contents —
     *  `official`/`searge`/`spigot`/custom. */
    val namespace: String = "official",
    /** Optional format hint — e.g. `"obf"` when the declared jar is
     *  obfuscated and must ride the raw→dev remap view. */
    val format: String? = null
) : Serializable
