package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Rift env wiring — 1.x `RiftMinecraftTransformer` parity. Rift is a
 * LaunchWrapper-family loader, not a jar merge: the rift jar is a
 * library dep, launchwrapper/mixin/ASM go on `minecraftLibraries`
 * (non-transitive, matching the 1.x classpath), and runs launch via
 * `net.minecraft.launchwrapper.Launch` with the side's
 * `RiftLoader*Tweaker`. Versions are MC-keyed — `loader("rift")`
 * resolves `org.dimdev:rift:<mcVersion>`.
 */
internal object RiftWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val mcVersion = environment.version.get()
        val declaredVersion = environment.spec().loader?.version
            ?.takeIf { it.isNotEmpty() } ?: mcVersion

        project.dependencies.add(
            environment.modImplementationConfigurationName,
            "org.dimdev:rift:$declaredVersion"
        )

        // 1.x pinned these non-transitively — rift's pom already
        // carries dimdev's mixin; keeping the trio explicit matches
        // the original classpath without letting it pull a tree
        listOf(
            "org.dimdev:mixin:0.7.11-SNAPSHOT",
            "net.minecraft:launchwrapper:1.12",
            "org.ow2.asm:asm:6.2",
            "org.ow2.asm:asm-commons:6.2",
            "org.ow2.asm:asm-tree:6.2"
        ).forEach { gav ->
            (project.dependencies.add(
                environment.minecraftLibrariesConfigurationName, gav
            ) as? ModuleDependency)?.isTransitive = false
        }

        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java
        )
        transform.sourceSet(environment.environmentId).runMutators +=
            org.gradle.api.Action { config ->
                // tweakers need LaunchWrapper's main, not the
                // version-json default (`net.minecraft.client.main.Main`)
                config.mainClass = "net.minecraft.launchwrapper.Launch"
                config.programArgs += listOf(
                    "--tweakClass",
                    if (config.name.contains("server", ignoreCase = true))
                        "org.dimdev.riftloader.launch.RiftLoaderServerTweaker"
                    else
                        "org.dimdev.riftloader.launch.RiftLoaderClientTweaker"
                )
            }
    }
}
