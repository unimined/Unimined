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
     *  `applies(context)` is checked against a per-binding context
     *  carrying the declared value (`bogus` in `nestedJars("bogus")`
     *  must fail, not validate as `null`). */
    fun resolve(ctx: EnvironmentFeature.FeatureContext)
            : List<EnvironmentFeature> = bindings.map { (key, value) ->
        val f = registry.require(key)
        val bindingCtx = object : EnvironmentFeature.FeatureContext {
            override val bindingValue = value
            override val side = ctx.side
            override val family = ctx.family
        }
        check(f.applies(bindingCtx)) {
            "feature '$key' does not apply to this environment " +
                    "(family=${ctx.family}, side=${ctx.side})"
        }
        f
    }

}
