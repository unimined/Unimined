package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import org.gradle.api.Action
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Groovy-DSL verb factory: Groovy's dispatch on a decorated
 * [RemapSurfaceSpec] resolves `verb(...)` to the extension *named*
 * `verb`; when that extension is a [Closure] it is invoked with the
 * call args — that is how `jst { }` / `unpick("a.unpick")` reach the
 * step DSL (Kotlin extension functions are static and cannot dispatch).
 *
 * The returned closure creates a fresh [StepSpec] per invocation,
 * feeds positional args to [extraArgs], and applies a trailing
 * closure argument with `DELEGATE_FIRST` on the new step (or an
 * `Action` argument as SAM).
 */
fun <S : StepSpec> RemapSurfaceSpec.stepVerb(
    type: Class<S>,
    extraArgs: (S, List<Any?>) -> Unit = { _, _ -> }
): Closure<Any?> = object : Closure<Any?>(this) {

    fun doCall(vararg args: Any?): Any? {
        val block = args.lastOrNull().takeIf { it is Closure<*> || it is Action<*> }
        val positional = if (block == null) args.toList() else args.dropLast(1)
        @Suppress("UNCHECKED_CAST")
        return step(type) { step ->
            extraArgs(step, positional)
            when (block) {
                is Closure<*> -> {
                    block.delegate = step
                    block.resolveStrategy = Closure.DELEGATE_FIRST
                    block.call(step)
                }
                is Action<*> -> (block as Action<S>).execute(step)
                else -> {}
            }
        }
    }

}
