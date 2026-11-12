package xyz.wagyourtail.unimined.runs

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.uniminedAdopt
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.internal.RunsWiring
import xyz.wagyourtail.unimined.api.util.Perf

/**
 * Plugin id `xyz.wagyourtail.unimined.runs` — materializes `runs { }`
 * entries into `run<Name>` [MinecraftRunTask]s per bound environment.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        Perf.syncFrom(target)
        target.extensions.create("uniminedRuns", RunsExtension::class.java)
        target.uniminedAdopt("runs", RunsExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("runs", RunsExtension::class.java)
        }
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, _ ->
                RunsWiring.wire(target, environment)
            }
        }
    }

}
