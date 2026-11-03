package xyz.wagyourtail.unimined.ide.internal

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Per-environment IDE wiring: registers `genIdeaRuns<Env>` /
 * `genEclipseRuns<Env>` file emitters and feeds env-bound run
 * models into the shared `genVsCodeRuns`/`genIdeRuns` tasks.
 * Models resolve at execution — this only wires providers.
 */
internal object IdeWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        val minecraft =
            project.extensions.getByType(MinecraftExtension::class.java)
        val transform =
            project.extensions.getByType(TransformExtension::class.java)
        val runsExtension =
            project.extensions.getByType(RunsExtension::class.java)
        val environmentId = environment.environmentId

        // env-bound run models — `environment.get()` resolves at
        // execution; unbound runs land on their first-bound env
        // (RunsWiring's convention), which this filters identically
        val models = project.provider {
            runsExtension.runs
                .filter { it.environment.get() == environmentId }
                .map { run ->
                    IdeModelResolver.resolveRun(
                        project, environment, run, sourceSet,
                        minecraft, transform
                    ).get()
                }
        }

        val suffix =
            if (environmentId == SourceSet.MAIN_SOURCE_SET_NAME) ""
            else environmentId.replaceFirstChar(Char::uppercase)

        val ideaTask = project.tasks.register(
            "genIdeaRuns$suffix",
            GenerateIdeRunsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description =
                "Generate IntelliJ run configurations for " +
                        "environment '$environmentId'"
            t.format.set(GenerateIdeRunsTask.IdeFormat.IDEA)
            t.projectName.set(project.name)
            t.moduleName.set("${project.name}.${sourceSet.name}")
            t.outputDir.set(
                project.layout.projectDirectory
                    .dir(".idea/runConfigurations")
            )
            t.runModels.set(models)
        }

        val eclipseTask = project.tasks.register(
            "genEclipseRuns$suffix",
            GenerateIdeRunsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description =
                "Generate Eclipse JDT .launch files for " +
                        "environment '$environmentId'"
            t.format.set(GenerateIdeRunsTask.IdeFormat.ECLIPSE)
            t.projectName.set(project.name)
            t.moduleName.set("${project.name}.${sourceSet.name}")
            t.outputDir.set(
                project.layout.projectDirectory.dir("launch")
            )
            t.runModels.set(models)
        }

        // vscode writes ONE launch.json across all envs — the
        // aggregate task lives at plugin apply; envs feed it
        project.tasks.named(
            "genVsCodeRuns", GenerateIdeRunsTask::class.java
        ) { t ->
            t.runModels.addAll(models)
        }

        // umbrella task
        project.tasks.named("genIdeRuns") { t ->
            t.dependsOn(ideaTask, eclipseTask)
        }
    }
}
