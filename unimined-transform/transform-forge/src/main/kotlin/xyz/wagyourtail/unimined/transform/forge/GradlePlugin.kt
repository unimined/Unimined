package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.net.URI

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
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            // every registered forge product contributes its maven repo;
            // `artifact()` metadata source — forge mavens host jar-only
            // artifacts (scala `_*_mc` FML libs, etc.) without poms
            // (1.x `minecraftForgeMaven` parity)
            LoaderRegistry().family("forge").flatMap { it.mavens }.toSet().forEach { maven ->
                target.repositories.maven { repo ->
                    repo.url = URI.create(maven)
                    repo.metadataSources { sources ->
                        sources.mavenPom()
                        sources.artifact()
                    }
                }
            }
            // JetBrains mavens — JST (sources-surface enrichers on
            // userdev-era forge) depends on IntelliJ platform modules
            // and `-jb`-suffixed republished deps that aren't on Central.
            // Content-filtered so they only serve those groups.
            // both JetBrains mavens get the same group filter —
            // intellij-repository hosts `com.jetbrains.intellij.*`
            // platform modules, intellij-dependencies the republished
            // third-party `-jb`/`intellij-*` variants
            target.repositories.maven { repo ->
                repo.url = URI.create("https://cache-redirector.jetbrains.com/intellij-repository")
                repo.content { content ->
                    content.includeGroupByRegex(
                        "com\\.jetbrains\\..*|com\\.intellij\\..*|org\\.jetbrains\\..*|com\\.github\\.weisj"
                    )
                }
            }
            target.repositories.maven { repo ->
                repo.url = URI.create("https://cache-redirector.jetbrains.com/intellij-dependencies")
                repo.content { content ->
                    content.includeGroupByRegex(
                        "com\\.jetbrains\\..*|com\\.intellij\\..*|org\\.jetbrains\\..*|com\\.github\\.weisj"
                    )
                }
            }
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
                        ForgeEnvironmentWiring.wire(target, environment)
                    }
                }
            }
        }
    }

}
