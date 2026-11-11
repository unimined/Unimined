package xyz.wagyourtail.unimined.api.util

import groovy.lang.Closure

/**
 * Groovy-DSL delegate body — the shared implementation behind every
 * `fun x(@DelegatesTo(T) action: Closure<*>)` overload: run the
 * closure with [target] as its DELEGATE_FIRST delegate so unqualified
 * member calls inside the block resolve against the DSL object, not
 * the build script.
 *
 * A bare `Action<T>`-only verb *accepts* a Groovy closure via SAM
 * coercion, but the delegate defaults to the script — `yarn(10)`
 * inside `file { }` would fail. The Closure twin is what fixes the
 * delegation; this helper keeps each twin a one-liner.
 */
fun <T : Any> Closure<*>.delegatingTo(target: T): Any? {
    delegate = target
    resolveStrategy = Closure.DELEGATE_FIRST
    return call()
}
