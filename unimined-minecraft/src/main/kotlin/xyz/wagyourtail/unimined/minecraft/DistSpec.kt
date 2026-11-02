package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.Action
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.Side

/**
 * Configuration surface for the dist preset verbs
 * ([MinecraftEnvironment.bta]/[MinecraftEnvironment.reindev]/
 * [MinecraftEnvironment.nsss]/[MinecraftEnvironment.custom]).
 *
 * Exposes the environment's product/version/side/loader config plus
 * the declared-source verbs ([file]/[maven]/[url]/[producer]) for
 * dists with no upstream index. Declared sources are routed through
 * [LegacyDistSpec] directly — unlike [MinecraftEnvironment.legacydist]
 * they do NOT force `provider("legacydist")`, so conversion-family
 * envs keep their `conversion` provider claim while still accepting
 * a user-declared jar (e.g. nsss, or a custom conversion dist).
 */
class DistSpec internal constructor(
    private val env: MinecraftEnvironment
) {

    /** The scoped product id this dist produces — preset to
     *  `minecraft:<name>` by the built-in presets and `custom:<name>`
     *  by [MinecraftEnvironment.custom]; override when the dist's
     *  game-family compat differs from the preset. */
    val product: Property<String> get() = env.product

    /** The dist's version (drives URLs + launch metadata). */
    val version: Property<String> get() = env.version

    /** Client/server/merged side. */
    val side: Property<Side> get() = env.side

    /** Provider override — presets set `conversion`; leave untouched
     *  for [MinecraftEnvironment.custom] so the declared-source seam
     *  negotiates. */
    val provider: Property<String> get() = env.provider

    /** Declare a local dist jar/zip as this environment's artifact. */
    fun file(path: Any) = env.legacyDist.file(path)

    /** Declare a maven-coordinate dist artifact. */
    fun maven(notation: String) = env.legacyDist.maven(notation)

    /** Declare a URL dist artifact, optionally sha256-pinned. */
    fun url(url: String, sha256: String? = null) =
        env.legacyDist.url(url, sha256)

    /** Declare an externally-produced dist artifact — the producer
     *  run must write [output]. */
    fun producer(
        executable: String,
        vararg args: String,
        jvmArgs: List<String> = emptyList(),
        output: String
    ) = env.legacyDist.producer(
        executable, *args, jvmArgs = jvmArgs, output = output
    )

    /** Declare the modloader layered on this dist. */
    fun loader(name: String, version: String) = env.loader(name, version)

    /** Groovy-DSL declared-source overload — `legacydist { file(...) }`
     *  is equally valid inside a dist block. */
    fun legacydist(action: Action<LegacyDistSpec>) =
        action.execute(env.legacyDist)

}
