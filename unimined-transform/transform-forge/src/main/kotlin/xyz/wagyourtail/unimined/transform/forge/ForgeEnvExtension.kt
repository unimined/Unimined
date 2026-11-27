package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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
class ForgeEnvExtension(
    private val spec: TransformEnvironmentSpec,
    private val environment: xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment,
    objects: ObjectFactory
) {

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
                // dev launches carry the prod (searge) view — obf
                // class names + FML's runtime deobf gives srg names
                // at load, matching the mod-source boundary
                environment.devRunViewName = environment.minecraftProdViewName
            }
            field = value
        }

    /**
     * JS coremod resource remap — default `true` for FG3-era
     * (userdev) envs: `.js` coremod resources (the forge universal
     * jar ships its own; mod jars carry them too) get srg-era names
     * rewritten to the dev namespace, since FML's csv naming
     * service has no class-name channel. Set `false` to opt out —
     * `env.forge { jsCoremodRemap.set(false) }`.
     *
     * Property-backed: the value is read when the deferred
     * `forge:jsCoremodRemap` step contribution materializes, so a
     * [Provider] set here resolves at pipeline materialization —
     * never during configuration. Groovy keeps assignment:
     * `jsCoremodRemap = false`.
     */
    val jsCoremodRemap: Property<Boolean> =
        objects.property(Boolean::class.java).apply { convention(true) }

    /** Direct-value setter for [jsCoremodRemap] — Groovy
     *  `jsCoremodRemap = false` resolves this bean setter. */
    fun setJsCoremodRemap(value: Boolean) =
        jsCoremodRemap.set(value)

    /** Provider setter for [jsCoremodRemap] — the provider resolves
     *  at pipeline materialization, not during configuration. */
    fun setJsCoremodRemap(value: Provider<out Boolean>) =
        jsCoremodRemap.set(value)

}
