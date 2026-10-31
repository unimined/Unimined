package xyz.wagyourtail.unimined.transform.forge

import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec

/**
 * Per-environment forge DSL object — registered as the `forge`
 * extension on each [MinecraftEnvironment] when this plugin applies.
 *
 * Groovy dispatches `env.forge { }` through [ExtensionAware]; Kotlin
 * reaches the same object via the `forge(Action)` sugar in
 * `ForgeDsl.kt`.
 */
class ForgeEnvExtension(private val spec: TransformEnvironmentSpec) {

    /**
     * Opt-in FG2 srg-namespace dev launch — `env.forge { srgRuntime = true }`.
     *
     * Setting `true` declares the `forge:srgRuntime` pipeline step:
     * the launch classpath carries the searge (mod-source view) MC
     * artifact and `-Dunimined.fg2.runtimeDeobf` keeps FML's runtime
     * deobf transformer registered. Default `false` — the dev-mapped
     * jar runs with the transformer stripped (1.x parity).
     */
    var srgRuntime: Boolean = false
        set(value) {
            if (value && !field) {
                spec.pipeline.step(
                    "forge:srgRuntime",
                    ForgeSrgRuntimeStepSpec::class.java
                ) {}
            }
            field = value
        }

}
