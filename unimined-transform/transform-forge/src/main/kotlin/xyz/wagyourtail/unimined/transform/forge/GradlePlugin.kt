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
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                    minecraft.onEnvironmentBound { environment, _ ->
                        ForgeEnvironmentWiring.wire(target, environment)
                    }
                }
            }
        }
    }

}
