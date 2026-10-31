package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Kotlin DSL sugar for the forge env extension — the extension is
 * registered as `forge` on each environment when this plugin
 * applies, so `env.forge { srgRuntime = true }` configures it.
 * Groovy resolves the same call through [ExtensionAware].
 */
fun MinecraftEnvironment.forge(action: Action<in ForgeEnvExtension>) =
    action.execute(
        (this as ExtensionAware).extensions
            .getByType(ForgeEnvExtension::class.java)
    )
