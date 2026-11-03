package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.net.URI

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.jarmod`.
 *
 * Loader-gated jarmod transform: for environments declaring
 * `loader("jarmod", <JarModAgent version>)`, wires the `jarMod<env>`
 * configuration into a merge step that injects its contents into the
 * dev Minecraft jar (1.x `JarModMinecraftTransformer` semantics —
 * this is also the substrate FG1 Forge and ModLoader builds reuse).
 * `JarModAgent` itself is the loader product; agent wiring for runs is
 * a follow-up phase.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            // jarmod mods run in the vanilla jar's own namespace
            minecraft.loaderModSourceNamespaces["jarmod"] = listOf("official")
            // family repos from the descriptors — includes
            // liteloader's artifact-only + legacy ivy feeds
            LoaderRegistry().family("jarmod").flatMap { it.repos }.toSet()
                .forEach { repo -> repo.registerOn(target.repositories) }
            // env-registration-time: configs must exist before the
            // script's `dependencies {}` block evaluates; the `jarmod`
            // extension must also exist before `minecraft { }` bodies
            // run so Groovy DSL can dispatch env.jarmod(...) etc.
            minecraft.environments.all { environment ->
                environment.extensions.add("jarmod", JarModEnvExtension(environment))
                JarModEnvironmentWiring.createConfigurations(target, environment)
            }
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                minecraft.onEnvironmentBound { environment, _ ->
                        val spec = target.extensions.getByType(
                        xyz.wagyourtail.unimined.transform.TransformExtension::class.java
                    ).sourceSet(environment.environmentId)
                    spec.installClaimContext(target, {
                        environment.loaderSpec.takeIf { it.isPresent }?.get()
                            ?.let { xyz.wagyourtail.unimined.transform.loader.LoaderRegistry().resolve(it.name) }
                    }, { environment })
                    // plugin applies lazily once an env declares a
                    // jarmod-family loader (LoaderDescriptor.transformPlugin)
                    // — loaderSpec is already populated at bound time,
                    // wire unconditionally and branch inside like forge
                    JarModEnvironmentWiring.wire(target, environment)
                }
            }
        }
    }

}
