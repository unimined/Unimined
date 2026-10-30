package xyz.wagyourtail.unimined.transform.bukkit

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.bukkit.internal.BukkitEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.net.URI

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.bukkit` — bukkit-family
 * transform: registers the product mavens from the descriptor
 * registry (spigot nexus, sonatype snapshots, papermc repo) and wires
 * each bound bukkit environment's namespace + artifact conventions.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            LoaderRegistry().family("bukkit").flatMap { it.mavens }.toSet().forEach { maven ->
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
                        BukkitEnvironmentWiring.wire(target, environment)
                    }
                }
            }
        }
    }

}
