package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState

/**
 * Per-environment jarmod DSL object — registered as the `jarmod`
 * extension on each [MinecraftEnvironment] when this plugin applies.
 *
 * Groovy-visible counterpart of the top-level extension functions in
 * `JarModDsl.kt`: Kotlin calls `env.jarmod("0.1.5")` via the sugar,
 * Groovy dispatches the same call through [ExtensionAware] to these
 * methods. The registered name (`jarmod`) also makes
 * `env.jarmod { }` closure configuration resolve to this object.
 */
class JarModEnvExtension(private val env: MinecraftEnvironment) {

    /** Declare a jarmod environment — `loader("jarmod", v)`. */
    fun jarmod(version: String) {
        env.loader("jarmod", version)
    }

    /** Declare an FG1-era Forge environment — `loader("fg1", v)`. */
    fun fg1(version: String) {
        env.loader("fg1", version)
    }

    /** 1.x `ForgeLikePatcher.customSearge` — user supplies their own
     *  searge/mcp chain; disables the auto `forgeBuiltinMCP` hop. */
    fun customSearge() {
        JarModEnvState.customSearge(env.environmentId, true)
    }

    /** Opt-in: resolve `org.ow2.asm:asm-all:5.2` for FML's `asm-all`
     *  dyn lib instead of the era's 4.x build. */
    fun useAsm52() {
        JarModEnvState.useAsm52(env.environmentId, true)
    }

    /** Register a JarModAgent transformer class. */
    fun jarmodTransformer(transform: String) {
        JarModEnvState.addTransforms(env.environmentId, listOf(transform))
    }

    /** Declare a jarModAgent transformer set. */
    fun jarmodTransforms(transforms: List<String>) {
        JarModEnvState.addTransforms(env.environmentId, transforms)
    }

    /** 1.x `jarModPatcher.deleteMetaInf` — strip the base jar's
     *  META-INF entirely during the merge. */
    fun deleteMetaInf(disable: Boolean = true) {
        JarModEnvState.deleteMetaInf(env.environmentId, disable)
    }
}
