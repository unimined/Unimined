package xyz.wagyourtail.unimined.mod

import java.io.Serializable

/**
 * Per-loader configuration declared through `mod { loader("<name>") { } }`.
 * The base section carries metadata concepts shared by most loaders
 * (entrypoints, mixin config references, access wideners, environment,
 * arbitrary custom keys); loader emitters may subclass to add loader-
 * specific fields, or simply read the shared bag.
 *
 * Sections are captured into the `generate<Env>ModMetadata` task inputs and
 * must remain [Serializable] — no Gradle types, providers, or project
 * references.
 */
open class LoaderSection : Serializable {

    /** Loader entrypoints by kind (`main`, `client`, `server`, ...). */
    val entrypoints: MutableMap<String, List<String>> = linkedMapOf()

    /** Mixin config paths this loader metadata should reference — manual
     *  resource paths, or `<name>.mixins.json` for `mixins {}` entries. */
    val mixinConfigs: MutableList<String> = mutableListOf()

    /** Access widener resource path (e.g. `examplemod.accesswidener`). */
    var accessWidener: String? = null

    /** Loader metadata environment override (`*`/`client`/`server`). */
    var environment: String? = null

    /** Arbitrary loader-specific metadata keys, emitted verbatim. */
    val custom: MutableMap<String, Serializable> = linkedMapOf()

    /** Register an entrypoint under the default `main` kind. */
    fun entrypoint(className: String) = entrypoint("main", className)

    /** Register an entrypoint under [kind]. */
    fun entrypoint(kind: String, className: String) {
        entrypoints[kind] = (entrypoints[kind] ?: emptyList()) + className
    }

    /** Reference a mixin config from this loader metadata. */
    fun mixinConfig(path: String) {
        mixinConfigs += path
    }

    /** Declare the access widener resource this loader should load. */
    fun accessWidener(path: String) {
        accessWidener = path
    }

    /** Loader-specific environment (`*`, `client`, `server`). */
    fun environment(value: String) {
        environment = value
    }

    /** Emit an arbitrary key/value pair into this loader's metadata. */
    fun custom(key: String, value: Serializable) {
        custom[key] = value
    }

}
