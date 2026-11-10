package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Key for the pending-action list stashed on an environment's extra
 * properties when [forge] runs before this plugin applies — the
 * plugin applies lazily at `afterEvaluate` (per-loader apply), so
 * `env.forge {}` inside a `minecraft {}`/`sourceSet {}` body always
 * evaluates before the `forge` extension exists.
 */
internal const val PENDING_FORGE_ACTIONS = "unimined.forge.pendingActions"

/**
 * Kotlin DSL sugar for the forge env extension — the extension is
 * registered as `forge` on each environment when this plugin
 * applies, so `env.forge { srgRuntime = true }` configures it.
 * Groovy resolves the same call through [ExtensionAware].
 *
 * When the extension isn't registered yet (lazy per-loader apply),
 * the action is queued on the environment and replayed by
 * `GradlePlugin` when the extension registers. Kotlin-DSL-only
 * surface — Groovy `env.forge {}` still requires eager dispatch.
 */
fun MinecraftEnvironment.forge(action: Action<in ForgeEnvExtension>) {
    val extension = (this as ExtensionAware).extensions
        .findByType(ForgeEnvExtension::class.java)
    if (extension != null) {
        action.execute(extension)
        return
    }
    @Suppress("UNCHECKED_CAST")
    val pending = (extensions.extraProperties
        .takeIf { it.has(PENDING_FORGE_ACTIONS) }
        ?.get(PENDING_FORGE_ACTIONS)
        as? MutableList<Action<in ForgeEnvExtension>>)
        ?: mutableListOf<Action<in ForgeEnvExtension>>().also {
            extensions.extraProperties.set(PENDING_FORGE_ACTIONS, it)
        }
    pending.add(action)
}
