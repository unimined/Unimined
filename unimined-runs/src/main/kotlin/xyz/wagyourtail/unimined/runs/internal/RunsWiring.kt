package xyz.wagyourtail.unimined.runs.internal

import xyz.wagyourtail.unimined.api.util.UniminedCache
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import xyz.wagyourtail.unimined.mclauncher.Natives
import xyz.wagyourtail.unimined.mclauncher.OperatingSystem
import xyz.wagyourtail.unimined.mclauncher.VersionChain
import xyz.wagyourtail.unimined.mclauncher.VersionData
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.runs.MinecraftRunTask
import xyz.wagyourtail.unimined.runs.RunConfig
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Per-environment run wiring: materializes each declared [RunConfig]
 * bound to an environment into a `run<Name>` [MinecraftRunTask], plus a
 * shared per-environment natives configuration fed by lazy version-JSON
 * coordinates.
 */
internal object RunsWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val extension = project.extensions.getByType(RunsExtension::class.java)
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val environmentId = environment.environmentId
        val sourceSet = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets.findByName(environmentId) ?: return

        // default client/server runs — `client`/`server` for the `main`
        // environment, `<env>Client`/`<env>Server` for others
        // `register` (not `maybeCreate`) keeps environment binding set
        // before `.all` callbacks realize the element — callbacks from
        // other environments then see the bound value and skip
        val clientName = if (environmentId == "main") "client" else "${environmentId}Client"
        val serverName = if (environmentId == "main") "server" else "${environmentId}Server"
        if (extension.runs.findByName(clientName) == null) {
            extension.runs.register(clientName) { it.environment.convention(environmentId) }
        }
        if (extension.runs.findByName(serverName) == null) {
            extension.runs.register(serverName) { it.environment.convention(environmentId) }
        }

        val natives = nativesConfiguration(project, environment, minecraft)

        extension.runs.all { run ->
            // unbound runs attach to the first environment that binds them;
            // explicitly-bound runs only materialize on their own env
            val bound = run.environment.orNull
            if (bound == null) {
                run.environment.convention(environmentId)
            } else if (bound != environmentId) {
                return@all
            }
            val taskName = "run${run.name.replaceFirstChar(Char::uppercase)}"
            if (project.tasks.findByName(taskName) != null) return@all
            registerRunTask(project, environment, run, sourceSet, minecraft, transform, natives)
        }
    }

    /** Register the `run<Name>` task for one run config on one environment. */
    private fun registerRunTask(
        project: Project,
        environment: MinecraftEnvironment,
        run: RunConfig,
        sourceSet: SourceSet,
        minecraft: MinecraftExtension,
        transform: TransformExtension,
        natives: FileCollection
    ) {
        val environmentId = environment.environmentId
        val taskName = "run${run.name.replaceFirstChar(Char::uppercase)}"
        val artifacts = minecraft.artifacts(environmentId)
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        val launchMetadata = artifacts.map { resolved ->
            resolved.launch
                ?: error("provider for environment '$environmentId' exposes no launch metadata")
        }

        // transform mutators run at execution: the provider captures only
        // the spec reference so run-name/loader decisions stay lazy
        val transformSpec = transform.sourceSet(environmentId)
        val mutated = project.provider {
            val spec = MutableRunConfigSpec(run.name)
            transformSpec.effectiveRunMutators().forEach { it.execute(spec) }
            spec
        }

        project.tasks.register(taskName, MinecraftRunTask::class.java) { task ->
            task.group = "unimined"
            task.description = "Launch Minecraft " +
                    (environment.version.orNull ?: "<unset>") +
                    " (${run.name}, env '$environmentId')"
            task.launchMetadata.set(launchMetadata)
            // SourceSet.runtimeClasspath — includes the dev MC artifact,
            // remapped mod views, and provider extras added as file
            // collections, none of which exist on the bare
            // `runtimeClasspath` configuration
            task.runtimeClasspath.from(sourceSet.runtimeClasspath)
            task.nativesClasspath.from(natives)
            task.assetIndexFile.set(artifacts.flatMap { it.assets }.flatMap { it.file })
            task.assetIndexId.set(artifacts.flatMap { it.assets }.map { it.id })
            task.runDirectory.set(
                project.layout.projectDirectory.dir(
                    run.workingDirectory.orElse("run/$environmentId-${run.name}").get()
                )
            )
            task.nativesDirectory.set(
                project.layout.buildDirectory.dir("unimined/natives/$environmentId")
            )
            // asset objects are sha1-addressed and identical across
            // projects/versions — global cache (mojang launcher model),
            // not build/ which clean wipes and projects duplicate
            task.assetsDirectory.set(
                project.layout.dir(project.provider {
                    UniminedCache.global(project).resolve("assets").toFile()
                })
            )
            task.side.set(run.side)
            task.mainClass.set(mutated.map { it.mainClass }.orElse(run.mainClass))
            task.jvmArgs.set(run.jvmArgs)
            task.programArgs.set(run.programArgs)
            task.mutatedJvmArgs.set(mutated.map { it.jvmArgs })
            task.mutatedProgramArgs.set(mutated.map { it.programArgs })
            task.mutatedEnvironment.set(mutated.map { it.environment })
            task.environmentVariables.set(run.environmentVariables)
            transformSpec.effectiveRunTaskMutators().forEach { it.execute(task) }
            task.stdin.set(run.stdin)
            task.launcherVersion.set(
                project.provider {
                    project.version.toString().ifBlank { "2.0.0" }
                })
            // `${key}` substitution values — environment-level defaults;
            // transform mutators add loader-specific keys through the
            // spec's `properties` (e.g. forge's `modules`,
            // `minecraft_classpath_file`, `source_roots`)
            task.launchProperties.put(
                "version_name",
                environment.version.orElse("unspecified")
            )
            task.launchProperties.put(
                "version_type",
                project.providers.provider { "release" })
            task.launchProperties.put(
                "assets_root",
                task.assetsDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put("assets_index", task.assetIndexId)
            task.launchProperties.put("assets_index_name", task.assetIndexId)
            task.launchProperties.put("asset_index", task.assetIndexId)
            task.launchProperties.put(
                "game_dir",
                task.runDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put(
                "game_directory",
                task.runDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put(
                "natives_root",
                task.nativesDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put(
                "natives_directory",
                task.nativesDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put(
                "library_directory",
                task.runDirectory.map {
                    it.dir("libraries").asFile.absolutePath
                })
            task.mutatedLaunchProperties.set(mutated.map {
                it.properties.mapValues { (_, v) -> v() }
            })
            task.features.set(run.features)
            task.username.set(run.username)
            task.width.set(run.width)
            task.height.set(run.height)
            task.downloadAssets.set(run.downloadAssets)
            task.offline.set(project.gradle.startParameter.isOffline)
            // `-Punimined.run.dryRun` prints the assembled launch
            // instead of exec'ing — the runs-verification surface
            task.dryRun.set(
                project.providers
                    .gradleProperty("unimined.run.dryRun")
                    .map { it.toBoolean() }
                    .orElse(false))
            task.requiredJavaVersion.set(environment.requiredJavaVersion)
            task.useToolchains.set(environment.useToolchains)
            // resolved at execution — toolchain resolution (and any
            // auto-provisioning) stays out of configuration time
            task.javaExecutable.set(environment.useToolchains.flatMap { use ->
                if (use) toolchains.launcherFor {
                    it.languageVersion.set(
                        environment.requiredJavaVersion.map(JavaLanguageVersion::of))
                }.map { it.executablePath.asFile.absolutePath }
                else project.providers.provider {
                    File(System.getProperty("java.home"), "bin/java").absolutePath
                }
            })
        }
    }

    /**
     * Resolvable view of this environment's native jars — native
     * classifier coordinates are computed from the effective version
     * document at resolution time (execution), so no metadata is fetched
     * during configuration.
     */
    private fun nativesConfiguration(
        project: Project,
        environment: MinecraftEnvironment,
        minecraft: MinecraftExtension
    ): FileCollection {
        val environmentId = environment.environmentId
        val name = if (environmentId == "main") {
            "minecraftNatives"
        } else {
            "minecraft${environmentId.replaceFirstChar(Char::uppercase)}Natives"
        }
        project.configurations.findByName(name)?.let { return project.files(it) }

        val artifacts = minecraft.artifacts(environmentId)
        val dependencies: DependencyHandler = project.dependencies
        val configuration = project.configurations.create(name) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.withDependencies { deps ->
                val metadata: LaunchMetadata = artifacts.get().launch ?: return@withDependencies
                val document = VersionData.parse(
                    VersionChain.resolve(
                        metadata.versionJson(metadata.rootId),
                        metadata::versionJson
                    )
                )
                Natives.coordinates(document, OperatingSystem.current()).forEach {
                    deps.add(dependencies.create(it))
                }
            }
        }
        return project.files(configuration)
    }

}
