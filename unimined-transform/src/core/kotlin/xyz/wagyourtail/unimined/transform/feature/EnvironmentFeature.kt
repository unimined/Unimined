package xyz.wagyourtail.unimined.transform.feature

/**
 * An environment feature — the wiring behind one capability binding
 * (`mixin`, `nestedJars("fabric")`, `modDiscovery("dli")`, …).
 * Loader modules contribute impls in their feature pack; `custom`
 * and bundled pipelines resolve declared bindings through the
 * [FeatureRegistry] against these.
 *
 * A feature owns a single concern's wiring across the surfaces it
 * touches — classpath contents, run-config args, prod repack —
 * declared via [surfaces] so applicability (side/era) can be
 * validated at selection time rather than silently no-oping.
 */
interface EnvironmentFeature {

    /** Capability binding key — `mixin`, `modDiscovery`, `nestedJars`. */
    val id: String

    /** Surfaces this feature writes — classpath, run config, prod
     *  repack — used for validation and conflict detection. */
    val surfaces: Set<Surface>

    /** Whether this feature applies to the given binding context
     *  (side/era/format checks) — false fails at selection time. */
    fun applies(context: FeatureContext): Boolean = true

    /** Surfaces a feature can write. */
    enum class Surface {
        CLASSPATH,
        RUN_CONFIG,
        PROD_REPACK,
        COMPILE,
    }

    /** Selection-time context a feature validates against. */
    interface FeatureContext {

        /** The declared binding value — `nestedJars("jarjar")` passes
         *  `"jarjar"`; `null` for a bare-flag binding. */
        val bindingValue: String?

        /** Environment side (`client`/`server`/`merged`). */
        val side: String?

        /** Loader family from the descriptor row (`fabric`/`forge`/…),
         *  `null` for rowless custom envs. */
        val family: String?

    }

}
