package xyz.wagyourtail.unimined.ide

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.DefaultTaskExecutionRequest
import xyz.wagyourtail.unimined.api.spi.IdeSyncTasks
import xyz.wagyourtail.unimined.ide.internal.GenerateIdeRunsTask
import xyz.wagyourtail.unimined.ide.internal.IdeWiring
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Plugin id `xyz.wagyourtail.unimined.ide` — IDE integration:
 * generated launch files for IntelliJ (`.idea/runConfigurations`),
 * VSCode (`.vscode/launch.json`), and Eclipse (`launch/` dir),
 * plus IDEA-sync regeneration via `idea.sync.active`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // umbrella — every emitter task registers itself under this;
        // provider resolves IdeSyncTasks contributions lazily so
        // plugins applying after this one still get wired
        target.tasks.register("genIdeRuns") { t ->
            t.group = "unimined"
            t.description =
                "Generate all IDE launch files (idea, vscode, eclipse)"
            t.dependsOn("genVsCodeRuns")
            t.dependsOn(target.provider {
                IdeSyncTasks.contributions(target)
            })
        }

        // vscode emits one merged launch.json across all envs —
        // registered eagerly; each bound env feeds it models
        target.tasks.register(
            "genVsCodeRuns", GenerateIdeRunsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description =
                "Generate VSCode .vscode/launch.json for all " +
                        "environments"
            t.format.set(GenerateIdeRunsTask.IdeFormat.VSCODE)
            t.projectName.set(target.name)
            t.moduleName.set(target.name)
            t.outputDir.set(
                target.layout.projectDirectory.dir(".vscode")
            )
        }

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft =
                target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, sourceSet ->
                IdeWiring.wire(target, environment, sourceSet)
            }
        }

        // IDEA sync hook — 1.x `scheduleTaskAfterIDEASync` port:
        // during a Gradle sync invoked by IntelliJ, append the
        // umbrella so configs regenerate as part of the sync.
        // genIdeRuns (not genIdeaRuns) — it always exists and carries
        // IdeSyncTasks contributions
        if (System.getProperty("idea.sync.active").toBoolean()) {
            val startParameter = target.gradle.startParameter
            val taskRequests = ArrayList(startParameter.taskRequests)
            taskRequests.add(
                DefaultTaskExecutionRequest(
                    listOf("genIdeRuns"), target.path, target.rootDir
                )
            )
            startParameter.setTaskRequests(taskRequests)
        }
    }

}
