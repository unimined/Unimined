package xyz.wagyourtail.unimined.api.util

import groovy.lang.Closure

/**
 * Groovy-DSL delegate body — the shared implementation behind every
 * `fun x(@DelegatesTo(T) action: Closure<*>)` overload: run the
 * closure with [target] as its DELEGATE_FIRST delegate so unqualified
 * member calls inside the block resolve against the DSL object, not
 * the build script.
 *
 * The closure is invoked with [target] as its single argument — so
 * `it`-style blocks (`remappedView("d","r") { it.sourceNamespace… }`)
 * and explicit-param blocks (`{ spec -> spec.x }`) bind exactly like
 * Gradle's own `ClosureBackedAction`, while unqualified calls still
 * hit the delegate. Zero-param closures (a Kotlin-side
 * `object : Closure` with `doCall()`, or a script's `{ -> }`) are
 * invoked bare — passing them an argument would throw
 * `MissingMethodException`.
 *
 * A bare `Action<T>`-only verb *accepts* a Groovy closure via SAM
 * coercion, but the delegate defaults to the script — `yarn(10)`
 * inside `file { }` would fail. The Closure twin is what fixes the
 * delegation; this helper keeps each twin a one-liner.
 */
fun <T : Any> Closure<*>.delegatingTo(target: T): Any? {
    delegate = target
    resolveStrategy = Closure.DELEGATE_FIRST
    return callDelegate()
}

/**
 * Invoke a config closure whose `delegate`/`resolveStrategy` were
 * already assigned: pass the delegate as the single argument when the
 * closure takes at least one parameter (the `it` convention), invoke
 * bare when it takes none.
 */
fun Closure<*>.callDelegate(): Any? =
    if (maximumNumberOfParameters == 0) call() else call(delegate)
