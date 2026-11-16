package xyz.wagyourtail.unimined.runs

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.uniminedAdopt
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.internal.RunsWiring
import xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
import xyz.wagyourtail.unimined.runs.task.RunsReportTask
import xyz.wagyourtail.unimined.api.util.Perf

/**
 * Plugin id `xyz.wagyourtail.unimined.runs` — materializes `runs { }`
 * entries into `run<Name>` [MinecraftRunTask]s per bound environment.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        Perf.syncFrom(target)
        val extension =
            target.extensions.create("uniminedRuns", RunsExtension::class.java)
        target.uniminedAdopt("runs", RunsExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("runs", RunsExtension::class.java)
        }

        // `genLaunchScripts` — aggregate the per-run
        // `genLaunchScript<Name>` script emitters (MDG
        // `createLaunchScripts` parity); RunsWiring dependsOn's each
        // script task as runs materialize
        target.tasks.register("genLaunchScripts") { t ->
            t.group = "unimined"
            t.description = "Emit runnable .sh/.cmd launch scripts " +
                "for every unimined run config"
        }

        // `uniminedRuns` — diagnostics listing of declared runs
        // (NG `runs` report parity); the provider resolves the
        // container lazily so env-bound runs registered after apply
        // still appear
        target.tasks.register("uniminedRuns", RunsReportTask::class.java) { t ->
            t.group = "unimined"
            t.description = "Report the declared unimined run " +
                "configurations (task, env, side, type, main class)"
            t.reportLines.set(target.provider {
                extension.runs.map { run ->
                    // effective env — the split-source-set redirect
                    // reports the env the run actually launches on
                    val env = extension.effectiveEnvironment(target, run) ?: "?"
                    "run${run.name.replaceFirstChar(Char::uppercase)}" +
                        "  env=$env" +
                        "  side=${run.side.orNull}" +
                        "  type=${run.type.orNull}" +
                        "  prod=${run.prod.orNull}" +
                        "  enabled=${run.enabled.orNull}" +
                        "  main=${run.mainClass.orNull ?: "<unset>"}"
                }
            })
        }

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, _ ->
                RunsWiring.wire(target, environment)
            }
        }
    }

}
