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
