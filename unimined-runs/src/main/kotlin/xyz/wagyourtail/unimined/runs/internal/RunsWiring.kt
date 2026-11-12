package xyz.wagyourtail.unimined.runs.internal

import xyz.wagyourtail.unimined.api.util.UniminedCache
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.runs.RunSide
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
import xyz.wagyourtail.unimined.runs.PrepareRunTask
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

        // device-code login is a standalone task — runs never prompt
        // mid-launch, they consume what this stored (0600 creds)
        if (project.tasks.findByName("loginMicrosoft") == null) {
            // config-time values — the exec-time action must not touch
            // `project` (configuration cache)
            val storePath = UniminedCache.global(project)
                .resolve("auth/credentials.json")
            val loginUser = project.findProperty("unimined.auth.username")
                ?.toString()
            val encrypt = project.providers.gradleProperty("unimined.auth.encrypt")
                .orNull?.toBoolean() == true
            project.tasks.register("loginMicrosoft") { t ->
                t.group = "unimined"
                t.description = "Microsoft device-code login — stores " +
                    "credentials for `auth { microsoft() }` runs"
                t.doLast {
                    val store: xyz.wagyourtail.unimined.mclauncher.SessionStore =
                        if (encrypt) {
                            xyz.wagyourtail.unimined.mclauncher
                                .EncryptedCredentialStore(storePath,
                                    xyz.wagyourtail.unimined.mclauncher
                                        .EncryptedCredentialStore
                                        .JavaKeyringProvider().key())
                        } else {
                            xyz.wagyourtail.unimined.mclauncher.CredentialStore(storePath)
                        }
                    xyz.wagyourtail.unimined.mclauncher.MicrosoftAuth(
                        store,
                        username = loginUser,
                        interactive = true
                    ).session()
                }
            }
        }

        // default client/server runs — `client`/`server` for the `main`
        // environment, `<env>Client`/`<env>Server` for others
        // `register` (not `maybeCreate`) keeps environment binding set
        // before `.all` callbacks realize the element — callbacks from
        // other environments then see the bound value and skip
        val clientName = if (environmentId == "main") "client" else "${environmentId}Client"
        val serverName = if (environmentId == "main") "server" else "${environmentId}Server"
        if (extension.runs.findByName(clientName) == null) {
            extension.runEnvironments[clientName] = environmentId
            extension.runs.register(clientName) { it.environment.convention(environmentId) }
        }
        if (extension.runs.findByName(serverName) == null) {
            extension.runEnvironments[serverName] = environmentId
            extension.runs.register(serverName) { it.environment.convention(environmentId) }
        }
        // prod-namespace variants — runProdClient/runProdServer launch
        // the compiled/remapped mod jar against the raw (production-
        // namespace) MC artifact, 1.x runObfClient/runObfServer parity
        val prodClientName =
            if (environmentId == "main") "prodClient" else "${environmentId}ProdClient"
        val prodServerName =
            if (environmentId == "main") "prodServer" else "${environmentId}ProdServer"
        if (extension.runs.findByName(prodClientName) == null) {
            extension.runEnvironments[prodClientName] = environmentId
            extension.runs.register(prodClientName) {
                it.environment.convention(environmentId)
                it.prod.convention(true)
            }
        }
        if (extension.runs.findByName(prodServerName) == null) {
            extension.runEnvironments[prodServerName] = environmentId
            extension.runs.register(prodServerName) {
                it.environment.convention(environmentId)
                it.prod.convention(true)
            }
        }

        // datagen/gametest child envs — materialize runs from the
        // PARENT's loader descriptor capabilities (combineWith excludes
        // LOADER, so the child carries none of its own). Deferred to
        // afterEvaluate: environments.all fires inside maybeCreate —
        // before the sourceSet { } action runs loader()/gametest(), so
        // loaderSpec and the aux-env lists are still empty at bind time
        // (same ordering trap isAuxEnv's managed property documents).
        project.afterEvaluate {
            RunsAuxWiring.wireAuxEnvs(project, environment, minecraft, extension)
        }

        val natives = nativesConfiguration(project, environment, minecraft)

        extension.runs.all { run ->
            // prefer the eager intended-env record — run.environment's
            // "main" convention resolves before register actions apply
            // on other envs' wire passes and would falsely match
            val bound = extension.runEnvironments[run.name]
                ?: run.environment.orNull
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
            val spec = MutableRunConfigSpec(run.name, run.prod.get())
            transformSpec.effectiveRunMutators().forEach { it.execute(spec) }
            spec
        }

        val runEnabled = run.enabled
        val prepare = project.tasks.register(
            "prepare${taskName.replaceFirstChar { it.uppercase() }}",
            PrepareRunTask::class.java
        ) { t ->
            t.description = "Stage run directory for $taskName"
            t.onlyIf { runEnabled.get() }
            t.runDirectory.set(
                project.layout.projectDirectory.dir(
                    run.workingDirectory.orElse("run/$environmentId-${run.name}").get()
                )
            )
            // mod jars staged into `stageInto` when a loader opts in —
            // dev: the compiled jar + dev-namespace mod buckets;
            // prod: the remapped jar + prod-namespace `*Remapped` views
            if (run.prod.get()) {
                // prod: the remapped (mod-source-namespace) jar + shipped
                // mod jars via `*Direct` — `*Remapped` views are
                // dev-namespace and wrong here
                t.modFiles.from(
                    project.provider {
                        val jarTask = project.tasks.findByName(
                            if (environmentId == "main") "remapJar"
                            else "remap${environmentId.replaceFirstChar(Char::uppercase)}Jar")
                        project.files(jarTask?.outputs?.files ?: project.files())
                    },
                    listOfNotNull(
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Direct"),
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Direct")
                    )
                )
            } else {
                t.modFiles.from(
                    project.tasks.named(
                        if (environmentId == "main") "jar"
                        else "${environmentId}Jar"),
                    // `*Direct` views — the buckets themselves are
                    // declarable-only; direct = mod-source-namespace
                    // pass-through (matches dev classpath for identity-
                    // namespace envs like the conversion products)
                    listOfNotNull(
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Direct"),
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Direct")
                    )
                )
            }
            transformSpec.effectivePrepareTaskMutators().forEach { it.execute(t) }
        }

        project.tasks.register(taskName, MinecraftRunTask::class.java) { task ->
            task.dependsOn(prepare)
            task.onlyIf { runEnabled.get() }
            run.beforeRunDeps.forEach { task.dependsOn(it) }
            run.beforeRunActions.forEachIndexed { i, action ->
                task.dependsOn(project.tasks.register(
                    "pre${taskName.replaceFirstChar { it.uppercase() }}$i",
                    org.gradle.api.DefaultTask::class.java
                ) { t -> action.execute(t) })
            }
            task.group = "unimined"
            task.description = "Launch Minecraft " +
                    (environment.version.orNull ?: "<unset>") +
                    " (${run.name}, env '$environmentId')"
            task.launchMetadata.set(launchMetadata)
            if (run.prod.get()) {
                // prod-namespace classpath (1.x runObf parity): the raw
                // MC artifact (production ships at source namespace),
                // the project's remapped (mod-source-namespace) jar,
                // shipped mod jars via `*Direct` pass-through views,
                // and the env's declared libraries. `*Remapped` views
                // are dev-namespace — wrong here; and no
                // sourceSet.output (dev classes don't belong on a prod
                // classpath).
                task.runtimeClasspath.from(
                    project.configurations.named(environment.minecraftRawViewName),
                    project.configurations.named(
                        environment.minecraftLibrariesConfigurationName),
                    project.provider {
                        project.files(
                            listOfNotNull(
                                project.configurations.findByName(
                                    "${environment.modImplementationConfigurationName}Direct"),
                                project.configurations.findByName(
                                    "${environment.modRuntimeOnlyConfigurationName}Direct")
                            )
                        )
                    },
                    project.provider {
                        val jarTask = project.tasks.findByName(
                            if (environmentId == "main") "remapJar"
                            else "remap${environmentId.replaceFirstChar(Char::uppercase)}Jar")
                        project.files(jarTask?.outputs?.files ?: project.files())
                    }
                )
            } else {
                // SourceSet.runtimeClasspath — includes the dev MC artifact,
                // remapped mod views, and provider extras added as file
                // collections, none of which exist on the bare
                // `runtimeClasspath` configuration
                task.runtimeClasspath.from(sourceSet.runtimeClasspath)
            }
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
            task.javaagents.from(run.javaagents)
            task.jvmArgs.set(run.jvmArgs)
            task.programArgs.set(run.programArgs)
            task.mutatedJavaagents.set(
                mutated.map { spec -> spec.javaagents.map { it.toString() } })
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
                artifacts.map { a ->
                    a.launch?.let { meta ->
                        VersionData.parse(
                            VersionChain.resolve(
                                meta.versionJson(meta.rootId),
                                meta::versionJson
                            )
                        ).type
                    }
                }.orElse("release"))
            task.launchProperties.put(
                "assets_root",
                task.assetsDirectory.map { it.asFile.absolutePath })
            task.launchProperties.put("assets_index", task.assetIndexId)
            task.launchProperties.put("assets_index_name", task.assetIndexId)
            task.launchProperties.put("asset_index", task.assetIndexId)
            // `${game_assets}` in pre-1.6-era minecraftArguments —
            // the resources dir the index gets materialized into
            task.launchProperties.put(
                "game_assets",
                task.runDirectory.map {
                    it.dir("resources").asFile.absolutePath
                })
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
            // auth{} is the typed form; `username` bridges in through
            // auth.username's convention (explicit auth.username wins)
            task.username.set(run.auth.username)
            task.authKind.set(run.auth.kind)
            task.authProvider.set(run.auth.provider)
            task.authStorePath.set(
                UniminedCache.global(project)
                    .resolve("auth/credentials.json").toString())
            task.authEncrypt.set(project.providers
                .gradleProperty("unimined.auth.encrypt")
                .map { it.toBoolean() }.orElse(false))
            task.authStoreCredentials.set(project.providers
                .gradleProperty("unimined.auth.storeCredentials")
                .map { it.toBoolean() }.orElse(true))
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
    /** Route a `g:n:v[:classifier]` natives coord through the same
     *  [applyLibraryRules] pipeline providers use — the default
     *  `natives-osx` drop and user `replaceLibraryVersion` rules apply
     *  to natives too (1.x parity). `null` = library dropped. */
    private fun substituteCoord(
        coord: String,
        spec: xyz.wagyourtail.unimined.api.spec.ProductSpec
    ): String? = xyz.wagyourtail.unimined.api.spec.applyLibraryRules(
        coord, spec.librarySubstitutions, spec.libraryRules,
        spec.libraryCoordFilters
    )

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
                // version-JSON natives coords are raw — run them through
                // the environment's library rules (e.g. ornithe's
                // org.lwjgl:* -> 2.9.4+legacyfabric.8 substitution, or the
                // natives-osx drop) so natives match the substituted
                // library jars, mirroring 1.x's libraryFilter
                val spec = environment.spec()
                Natives.coordinates(document, OperatingSystem.current()).forEach {
                    substituteCoord(it, spec)?.let { c ->
                        deps.add(dependencies.create(c))
                    }
                }
            }
        }
        return project.files(configuration)
    }

}
