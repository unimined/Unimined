package xyz.wagyourtail.unimined.mod

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.internal.ModPackagingWiring

/**
 * Plugin id `xyz.wagyourtail.unimined.mod` — the `mod {}` packaging host.
 * Owns the loader-neutral model, generated mixin configs, and
 * `generate<Env>ModMetadata` per bound environment; loader metadata
 * emitters (`fabric.mod.json`, `mods.toml`, ...) plug in through the
 * `ModMetadataEmitter` ServiceLoader SPI, bundled with their transform
 * plugins.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("mod", ModExtension::class.java, target)

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, sourceSet ->
                ModPackagingWiring.wire(target, environment, sourceSet)
            }
        }
    }

}
