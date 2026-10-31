package xyz.wagyourtail.unimined.transform

import xyz.wagyourtail.unimined.transform.feature.EnvironmentFeature
import xyz.wagyourtail.unimined.transform.feature.FeatureRegistry

/**
 * Declared capability bindings for an environment — `features { }`.
 * Typed values, not bare flags: `nestedJars("jarjar")`,
 * `modDiscovery("dli")`, `mixin` resolve through [FeatureRegistry];
 * unresolvable keys fail at selection, not silently.
 */
class FeaturesSpec(val registry: FeatureRegistry) {

    /** Binding key → declared value (`null` = bare flag). */
    val bindings = mutableMapOf<String, String?>()

    /** Manually-registered custom impls — shadow bundled keys. */
    fun register(feature: EnvironmentFeature) {
        registry.register(feature)
    }

    /** Declare a binding — `nestedJars("jarjar")` / `mixin`. */
    fun binding(key: String, value: String? = null) {
        registry.require(key) // fail at declaration on unknown keys
        bindings[key] = value
    }

    /** Resolve all declared bindings — selection-time validation:
     *  `applies(context)` false fails loudly. */
    fun resolve(ctx: EnvironmentFeature.FeatureContext)
            : List<EnvironmentFeature> = bindings.map { (key, _) ->
        val f = registry.require(key)
        check(f.applies(ctx)) {
            "feature '$key' does not apply to this environment " +
                    "(family=${ctx.family}, side=${ctx.side})"
        }
        f
    }

}
