package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.mapping.UniminedRepos
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.api.util.Perf

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.forge`.
 *
 * Forge-family transform: registers forge-product maven repos (from the
 * descriptor registry — forge/lexforge/neoforge/cleanroom), then wires
 * each bound environment's provider convention and access-transformer
 * pipeline — only when the environment declares a forge-family loader.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        Perf.syncFrom(target)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            // every registered forge product contributes its maven repo;
            // `artifact()` metadata source — forge mavens host jar-only
            // artifacts (scala `_*_mc` FML libs, etc.) without poms
            // (1.x `minecraftForgeMaven` parity)
            LoaderRegistry().family("forge").flatMap { it.repos }.toSet()
                .forEach { repo -> repo.registerOn(target.repositories) }
            // JetBrains mavens — JST (sources-surface enrichers on
            // userdev-era forge) depends on IntelliJ platform modules
            // and `-jb`-suffixed republished deps that aren't on Central.
            // Content-filtered so they only serve those groups.
            // both JetBrains mavens get the same group filter —
            // intellij-repository hosts `com.jetbrains.intellij.*`
            // platform modules, intellij-dependencies the republished
            // third-party `-jb`/`intellij-*` variants
            // both JetBrains mavens get the same group filter —
            // intellij-repository hosts `com.jetbrains.intellij.*`
            // platform modules, intellij-dependencies the republished
            // third-party `-jb`/`intellij-*` variants
            UniminedRepos(target).jetbrainsIntellijRepository.ensure()
            UniminedRepos(target).jetbrainsIntellijDependencies.ensure()
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                    minecraft.onEnvironmentBound { environment, _ ->
                        val spec = target.extensions.getByType(
                        xyz.wagyourtail.unimined.transform.TransformExtension::class.java
                    ).sourceSet(environment.environmentId)
                    spec.installClaimContext(target, {
                        environment.loaderSpec.takeIf { it.isPresent }?.get()
                            ?.let { xyz.wagyourtail.unimined.transform.loader.LoaderRegistry().resolve(it.name) }
                    }, { environment })
                        // per-env `forge` DSL object — `env.forge {
                        // srgRuntime = true }` and friends
                        val forgeExt = ForgeEnvExtension(spec)
                        (environment as org.gradle.api.plugins.ExtensionAware)
                            .extensions.add("forge", forgeExt)
                        // replay `env.forge {}` calls that ran while
                        // the extension didn't exist yet (lazy apply)
                        @Suppress("UNCHECKED_CAST")
                        (environment.extensions.extraProperties
                            .takeIf { it.has(PENDING_FORGE_ACTIONS) }
                            ?.get(PENDING_FORGE_ACTIONS)
                            as? List<org.gradle.api.Action<in ForgeEnvExtension>>
                            )?.forEach { it.execute(forgeExt) }
                        ForgeEnvironmentWiring.wire(target, environment)
                    }
                }
            }
        }
    }

}
