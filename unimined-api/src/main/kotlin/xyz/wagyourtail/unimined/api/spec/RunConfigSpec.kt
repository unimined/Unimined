package xyz.wagyourtail.unimined.api.spec

/**
 * Mutable view of a launch configuration that transforms may adjust —
 * e.g. forge-family transforms inject `tweakClass`/launchwrapper args,
 * fabric swaps the main class. Corresponds to the 1.x
 * `applyClientRunTransform`/`applyServerRunTransform` hook.
 */
interface RunConfigSpec {

    /** Run configuration name, e.g. `"client"`, `"server"`. */
    val name: String

    var mainClass: String?

    val jvmArgs: MutableList<String>

    val programArgs: MutableList<String>

    val environment: MutableMap<String, String>

}
