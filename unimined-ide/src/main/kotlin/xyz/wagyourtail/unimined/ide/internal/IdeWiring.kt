package xyz.wagyourtail.unimined.ide.internal

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.ide.task.GenerateIdeRunsTask

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
        // (RunsWiring's convention), which this filters identically.
        // `ideConfigGenerated=false` drops the run before model
        // resolution so suppressed runs cost nothing.
        val models = project.provider {
            runsExtension.runs
                .filter { includeRun(project, it, environmentId) }
                .map { run ->
                    IdeModelResolver.resolveRun(
                        project, environment, run, sourceSet,
                        minecraft, transform
                    ).get()
                }
        }

        // verb<Env>Noun naming: `gen<Fabric>IdeaRuns` — main keeps
        // the bare `genIdeaRuns`/`genEclipseRuns`; the pre-convention
        // `genIdeaRuns<Env>`/`genEclipseRuns<Env>` names stay as
        // deprecated aliases
        val suffix =
            if (environmentId == SourceSet.MAIN_SOURCE_SET_NAME) ""
            else environmentId.replaceFirstChar(Char::uppercase)

        val ideaTask = project.tasks.register(
            "gen${suffix}IdeaRuns",
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
            t.argfilesDir.set(
                project.layout.buildDirectory.dir("unimined/ideArgs")
            )
            t.runModels.set(models)
        }

        val eclipseTask = project.tasks.register(
            "gen${suffix}EclipseRuns",
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
            t.argfilesDir.set(
                project.layout.buildDirectory.dir("unimined/ideArgs")
            )
            t.runModels.set(models)
        }

        // vscode writes ONE launch.json across all envs — the
        // aggregate task lives at plugin apply; envs feed it
        project.tasks.named(
            "genVsCodeRuns", GenerateIdeRunsTask::class.java
        ) { t ->
            t.argfilesDir.set(
                project.layout.buildDirectory.dir("unimined/ideArgs")
            )
            t.runModels.addAll(models)
        }

        // umbrella task
        project.tasks.named("genIdeRuns") { t ->
            t.dependsOn(ideaTask, eclipseTask)
        }

        if (environmentId != SourceSet.MAIN_SOURCE_SET_NAME) {
            project.registerDeprecatedTaskAlias(
                "genIdeaRuns$suffix", ideaTask)
            project.registerDeprecatedTaskAlias(
                "genEclipseRuns$suffix", eclipseTask)
        }
    }

    /** Whether [run] emits an IDE run configuration for
     *  [environmentId] — effective-env match (the
     *  `splitEnvironmentSourceSets` redirect lands client runs on the
     *  split client env) plus the run's `ideConfigGenerated` opt-out.
     *  Internal for tests. */
    internal fun includeRun(
        project: Project,
        run: xyz.wagyourtail.unimined.runs.RunConfig,
        environmentId: String
    ): Boolean {
        val env = project.extensions
            .findByType(RunsExtension::class.java)
            ?.effectiveEnvironment(project, run) ?: run.environment.orNull
        return env == environmentId && run.ideConfigGenerated.get()
    }
}
