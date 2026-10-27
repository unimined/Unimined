package xyz.wagyourtail.unimined.source

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.source.internal.GenModSourcesWiring
import xyz.wagyourtail.unimined.source.internal.GenSourcesWiring

/**
 * Plugin id `xyz.wagyourtail.unimined.source` — the decompiler SPI host.
 * Wires `genSources`/`gen<Env>Sources` per bound environment; engines
 * (`unimined-decompiler-*`) self-register via ServiceLoader.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("source", SourceExtension::class.java)

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                // the dev view the decompiler consumes is created by the
                // transform plugin's classpath wiring
                target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                    val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
                    minecraft.onEnvironmentBound { environment, sourceSet ->
                        GenSourcesWiring.wire(target, environment, sourceSet)
                        GenModSourcesWiring.wire(target, environment, sourceSet)
                    }
                }
            }
        }
    }

}
