package xyz.wagyourtail.unimined.api.util

/**
 * Opt-out for the Groovy-DSL parity guard (`GroovyTwinParityTest`):
 * marks a class or method as *not* a DSL surface, so public members
 * taking `Action<T>`/Kotlin function types need no `@DelegatesTo`
 * `Closure` twin.
 *
 * Apply to Kotlin-only seams — wiring SPIs consumed from transform
 * code, internal pipeline helpers, and programmatic utilities that
 * Groovy build scripts never invoke. Do **not** use it to skip a
 * twin a user could plausibly call from a `build.gradle` — write the
 * one-line `Closure` overload instead; it exists so the closure's
 * delegate resolves to the DSL object rather than the script.
 *
 * @property reason why no Groovy twin exists (audited in review).
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoGroovyTwin(val reason: String)
