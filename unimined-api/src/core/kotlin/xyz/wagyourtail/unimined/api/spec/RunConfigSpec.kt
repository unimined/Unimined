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

    /**
     * Whether the run launches against the production-namespace
     * classpath (the remapped jar + prod views). Mutators must gate
     * dev-only classpath surgery on this — dev class dirs do not
     * belong on a prod launch.
     */
    val prod: Boolean

    var mainClass: String?

    /**
     * Java agent jars attached to the launch — rendered as
     * `-javaagent:<path>` ahead of `jvmArgs`. Prefer this over raw
     * `-javaagent:` strings so the task can treat the jars as file
     * inputs (up-to-date checks, config-cache fingerprinting).
     */
    val javaagents: MutableList<java.nio.file.Path>

    val jvmArgs: MutableList<String>

    val programArgs: MutableList<String>

    val environment: MutableMap<String, String>

    /**
     * `${key}` substitution suppliers applied to `jvmArgs`,
     * `programArgs`, and `environment` at launch — the 1.x
     * `RunConfig.properties` seam. Transforms register
     * loader-specific keys (`modules`, `minecraft_classpath_file`,
     * `source_roots`...); the task supplies the environment-level
     * ones (`assets_root`, `game_dir`, `natives_root`,
     * `version_type`...).
     */
    val properties: MutableMap<String, () -> String>

}
