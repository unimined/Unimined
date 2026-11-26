package xyz.wagyourtail.unimined.runs.internal

import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.failProblem
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
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
import xyz.wagyourtail.unimined.minecraft.ModGroups
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
import xyz.wagyourtail.unimined.runs.task.PrepareRunTask
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
            extension.runs.register(clientName) {
                it.environment.convention(environmentId)
                // `type` conventions to the run name — env-scoped names
                // (`lexforge7Client`) aren't the run kind; loader run
                // mutators gate on `client`/`server` and would skip
                // the launch config entirely otherwise
                it.type.convention("client")
            }
        }
        if (extension.runs.findByName(serverName) == null) {
            extension.runEnvironments[serverName] = environmentId
            extension.runs.register(serverName) {
                it.environment.convention(environmentId)
                it.type.convention("server")
            }
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
                it.type.convention("client")
            }
        }
        if (extension.runs.findByName(prodServerName) == null) {
            extension.runEnvironments[prodServerName] = environmentId
            extension.runs.register(prodServerName) {
                it.environment.convention(environmentId)
                it.prod.convention(true)
                it.type.convention("server")
            }
        }

        // datagen/gametest child envs — materialize runs from the
        // PARENT's loader descriptor capabilities (combineWith excludes
        // LOADER, so the child carries none of its own). Deferred to
        // afterEvaluate: environments.all fires inside maybeCreate —
        // before the sourceSet { } action runs loader()/gametest(), so
        // loaderSpec and the aux-env lists are still empty at bind time
        // (same ordering trap isAuxEnv's managed property documents).
        project.afterDslEval {
            RunsAuxWiring.wireAuxEnvs(project, environment, minecraft, extension)
            RunsAuxWiring.wireRunTypes(project, environment, extension)
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
            }
            val taskName = "run${run.name.replaceFirstChar(Char::uppercase)}"
            // claiming defers to post-DSL — `splitEnvironmentSourceSets`
            // retargets client-side runs bound to `main` onto its split
            // client env (the common env's intersection jar can't
            // launch a client), and the DSL's final state is the only
            // reliable basis for which env pass owns the task
            project.afterDslEval {
                if (extension.effectiveEnvironment(project, run)
                        != environmentId) return@afterDslEval
                if (project.tasks.findByName(taskName) != null)
                    return@afterDslEval
                registerRunTask(
                    project, environment, run, sourceSet,
                    minecraft, transform, natives)
            }
        }
    }

    /**
     * The mod-jar task a run stages — `jar`/`remapJar` for `main`
     * and for a *split client* env: its own `<env>Jar` holds
     * client-only output, while the packaged mod artifact is the
     * parent's combined jar (client output is folded in by
     * [MinecraftExtension.splitEnvironmentSourceSets]). Other envs
     * keep their own `<env>Jar`/`remap<Env>Jar`.
     */
    private fun modJarTaskName(
        minecraft: MinecraftExtension,
        environment: MinecraftEnvironment,
        remap: Boolean
    ): String {
        val environmentId = environment.environmentId
        val splitClient = environmentId != SourceSet.MAIN_SOURCE_SET_NAME &&
            minecraft.environments.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                ?.splitClientEnvironment?.orNull == environmentId
        val base = if (environmentId == SourceSet.MAIN_SOURCE_SET_NAME ||
            splitClient) "jar" else "${environmentId}Jar"
        return if (remap) "remap${base.replaceFirstChar(Char::uppercase)}"
        else base
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
                ?: project.failProblem("runs/launch-metadata",
                    "unimined: provider for environment '$environmentId' " +
                        "exposes no launch metadata")
        }

        // transform mutators run at execution: the provider captures only
        // the spec reference so run-name/loader decisions stay lazy
        val transformSpec = transform.sourceSet(environmentId)
        val mutated = project.provider {
            val spec = MutableRunConfigSpec(run.name, run.prod.get()).apply {
                type = run.type.orElse(run.name).get()
            }
            transformSpec.effectiveRunMutators().forEach { it.execute(spec) }
            spec
        }

        // mods{} declared groups — `run.loadedMods` picks a subset
        // (empty = all declared). Group outputs join the launch
        // classpath; loaders grouping classpath entries by mod get the
        // selection serialized: `classpathGroups`-capable descriptors →
        // that system property (fabric-loader #585 — groups separated
        // by a doubled path separator, files by a single one);
        // `modGroupsEnv`-capable descriptors → that env var's `id%%dir`
        // entries appended (MOD_CLASSES — Loom `mods {}` / NFRT
        // parity). Serialization lives in ModGroups so the run task,
        // the JUnit twin, and IDE-emitted configs can't drift; groups
        // are a dev-launch channel only (ModGroups.selected returns
        // empty on prod).
        val selectedModGroups = project.provider {
            ModGroups.selected(
                environment, run.loadedMods.orNull.orEmpty(), run.prod.get())
        }
        // the loader descriptor is a provider — `registerRunTask`
        // fires inside runs.all when the auto-registered env runs
        // realize, which precedes the env's own `loader()` configure;
        // reading loaderSpec at execution is what resolves it then.
        // Only strings (the capability channel names) and the
        // serialized group value reach task state — the descriptor
        // itself never does (it isn't CC-serializable).
        val runDescriptor = project.provider {
            environment.loaderSpec.orNull?.let {
                xyz.wagyourtail.unimined.transform.loader
                    .LoaderRegistry().resolve(it.name)
            }
        }

        // environment-level `${key}` substitution defaults — one map
        // shared by the launch task's `launchProperties` and the JUnit
        // twin's env-value substitution so both resolve identically
        val envLaunchDefaults = project.provider {
            val runDir = project.layout.projectDirectory.dir(
                run.workingDirectory
                    .orElse("run/$environmentId-${run.name}").get()
            ).asFile
            val nativesDir = project.layout.buildDirectory
                .dir("unimined/natives/$environmentId").get().asFile
            // asset objects are sha1-addressed and identical across
            // projects/versions — global cache (mojang launcher model)
            val assetsRoot = UniminedCache.global(project)
                .resolve("assets").toFile()
            val resolved = artifacts.get()
            val assetIndexId = resolved.assets.orNull?.id
            mapOf(
                "version_name" to
                    (environment.version.orNull ?: "unspecified"),
                "version_type" to (resolved.launch?.let { meta ->
                    VersionData.parse(
                        VersionChain.resolve(
                            meta.versionJson(meta.rootId),
                            meta::versionJson)
                    ).type
                } ?: "release"),
                "assets_root" to assetsRoot.absolutePath,
                "assets_index" to (assetIndexId ?: ""),
                "assets_index_name" to (assetIndexId ?: ""),
                "asset_index" to (assetIndexId ?: ""),
                // `${game_assets}` in pre-1.6-era minecraftArguments —
                // the resources dir the index gets materialized into
                "game_assets" to
                    runDir.resolve("resources").absolutePath,
                "game_dir" to runDir.absolutePath,
                "game_directory" to runDir.absolutePath,
                "natives_root" to nativesDir.absolutePath,
                "natives_directory" to nativesDir.absolutePath,
                "library_directory" to
                    runDir.resolve("libraries").absolutePath,
            )
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
            // `modsDir` capability → the run-dir subdir `modFiles`
            // stage into (conversion `mods/`, forge-family `mods/`).
            // Unset = no staging. Conditional staging rules a
            // capability can't express (bukkit's feature-bound
            // `plugins/`) ride `prepareTaskMutators` below instead.
            val caps = runDescriptor.orNull?.capabilities
            caps?.get("modsDir")?.let(t.stageInto::set)
            // default staged set: dev = the compiled jar + `*Direct`
            // mod views; prod = the remapped jar + `*Direct` views.
            // Skipped on loaders that discover classpath mods
            // natively (`modGroupsEnv`/`classpathGroups` — forge's
            // ClasspathLocator covers `-cp`, MOD_CLASSES covers the
            // exploded dirs): the same mods would register twice —
            // a folder copy plus the classpath entry (`Duplicate key`
            // in LoadingModList). `run.modFolderFiles` — explicit
            // per-run jars for folder discovery — always lands.
            if (caps == null || (!caps.containsKey("modGroupsEnv") &&
                    !caps.containsKey("classpathGroups"))) {
                if (run.prod.get()) {
                    // prod: the remapped (mod-source-namespace) jar + shipped
                    // mod jars via `*Direct` — `*Remapped` views are
                    // dev-namespace and wrong here
                    t.modFiles.from(
                        project.provider {
                            val jarTask = project.tasks.findByName(
                                modJarTaskName(minecraft, environment,
                                    remap = true))
                            project.files(jarTask?.outputs?.files ?: project.files())
                        },
                        listOfNotNull(
                            project.configurations.findByName(
                                "${environment.modApiConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modImplementationConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modRuntimeOnlyConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modLocalRuntimeConfigurationName}Direct")
                        )
                    )
                } else {
                    t.modFiles.from(
                        project.tasks.named(
                            modJarTaskName(minecraft, environment,
                                remap = false)),
                        // `*Direct` views — the buckets themselves are
                        // declarable-only; direct = mod-source-namespace
                        // pass-through (matches dev classpath for identity-
                        // namespace envs like the conversion products)
                        listOfNotNull(
                            project.configurations.findByName(
                                "${environment.modApiConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modImplementationConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modRuntimeOnlyConfigurationName}Direct"),
                            project.configurations.findByName(
                                "${environment.modLocalRuntimeConfigurationName}Direct")
                        )
                    )
                }
            }
            t.modFiles.from(run.modFolderFiles)
            transformSpec.effectivePrepareTaskMutators().forEach { it.execute(t) }
        }

        // per-run dependency bucket — `dependencies {
        // "run<Name>Dependencies"("g:a:v") }` appends coords to this
        // run's launch classpath only
        val runDepsConf = project.configurations.register(
            "${taskName}Dependencies") {
            it.description =
                "Launch classpath additions for the '${run.name}' run"
            it.isCanBeConsumed = false
            it.isVisible = false
        }

        // `pre<Name><i>` companions for `beforeRun(Action)` —
        // registered eagerly here: the task configure action runs on
        // realization, when the mutation guard forbids task-container
        // writes (see the contributions note below)
        val beforeRunTasks = run.beforeRunActions.mapIndexed { i, action ->
            project.tasks.register(
                "pre${taskName.replaceFirstChar { it.uppercase() }}$i",
                org.gradle.api.DefaultTask::class.java
            ) { t ->
                // execution-time — the hook contract is "before the run
                // launches": fixtures stage files and validate build
                // outputs here, none of which exist at creation time
                t.doLast { action.execute(t) }
            }
        }

        // shared between `run<Name>` and `genLaunchScript<Name>` — the
        // script task resolves the identical launch but writes
        // `.sh`/`.cmd` instead of exec'ing
        val configureRunTask = Action<MinecraftRunTask> { task ->
            task.dependsOn(prepare)
            task.onlyIf { runEnabled.get() }
            run.beforeRunDeps.forEach { task.dependsOn(it) }
            beforeRunTasks.forEach { task.dependsOn(it) }
            task.group = "unimined"
            task.description = "Launch Minecraft " +
                    (environment.version.orNull ?: "<unset>") +
                    " (${run.name}, env '$environmentId')"
            task.launchMetadata.set(launchMetadata)
            if (run.prod.get()) {
                // prod-namespace classpath (1.x runObf parity): MC in
                // the env's production view — `minecraftProd` (the
                // mod-ship namespace) by default, overridable to the
                // raw artifact where production launches via runtime
                // deobf (FG2-era forge) — plus the project's remapped
                // (prod-namespace) jar, shipped mod jars via `*Direct`
                // pass-through views, and the env's declared
                // libraries. `*Remapped` views are dev-namespace —
                // wrong here; and no sourceSet.output (dev classes
                // don't belong on a prod classpath).
                task.runtimeClasspath.from(
                    project.configurations.named(environment.prodRunViewName),
                    project.configurations.named(
                        environment.minecraftLibrariesConfigurationName),
                    project.provider {
                        project.files(
                            listOfNotNull(
                                project.configurations.findByName(
                                    "${environment.modApiConfigurationName}Direct"),
                                project.configurations.findByName(
                                    "${environment.modImplementationConfigurationName}Direct"),
                                project.configurations.findByName(
                                    "${environment.modRuntimeOnlyConfigurationName}Direct"),
                                project.configurations.findByName(
                                    "${environment.modLocalRuntimeConfigurationName}Direct")
                            )
                        )
                    },
                    project.provider {
                        val jarTask = project.tasks.findByName(
                            modJarTaskName(minecraft, environment,
                                remap = true))
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
            // per-run additions — files + the `run<Name>Dependencies`
            // bucket append after the env classpath either way
            task.runtimeClasspath.from(
                run.additionalRuntimeClasspath, runDepsConf)
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
            // no `.orElse` here — OrElseValueProducer evaluates the
            // left side during task-dependency visiting to check
            // mayHaveValue, which would force `mutated` (transform run
            // mutators — file reads + detached resolutions) at task-
            // graph time. The fallback stays inside the map so the
            // whole chain lazily waits for execution.
            task.mainClass.set(mutated.map { it.mainClass ?: run.mainClass.orNull })
            task.javaagents.from(run.javaagents)
            task.jvmArgs.set(run.jvmArgs)
            // sponge-mixin `mixin.configs` global property — read via
            // System.getProperty on ModLauncher-era services (FG3+/
            // modern forge). LaunchWrapper-era services read it from
            // `Launch.blackboard` instead, where sysprops never land —
            // that era registers configs via the `MixinConfigs` +
            // `TweakClass` manifest attributes emitted by
            // GenerateModMetadataTask into exploded dev resources.
            // Inert where no sponge-mixin runtime is on the classpath.
            task.jvmArgs.addAll(project.provider {
                val configs = environment.mixinConfigs.get()
                if (configs.isEmpty()) emptyList()
                else listOf("-Dmixin.configs=" + configs.joinToString(","))
            })
            task.programArgs.set(run.programArgs)
            task.mutatedJavaagents.set(
                mutated.map { spec -> spec.javaagents.map { it.toString() } })
            task.mutatedJvmArgs.set(mutated.map { it.jvmArgs })
            task.mutatedProgramArgs.set(mutated.map { it.programArgs })
            task.mutatedEnvironment.set(mutated.map { it.environment })
            task.environmentVariables.set(run.environmentVariables)
            task.systemProperties.set(run.systemProperties)
            // selected mods{} groups join the launch classpath and
            // serialize onto the loader's mod-grouping channel
            // (`classpathGroups` sysprop / `modGroupsEnv` env var —
            // see ModGroups for the formats + selection rule)
            task.runtimeClasspath.from(selectedModGroups.map { groups ->
                groups.flatMap { it.launchFiles() }
            })
            task.systemProperties.putAll(project.provider {
                val prop = runDescriptor.orNull?.capabilities
                    ?.get("classpathGroups") ?: return@provider emptyMap()
                mapOf(prop to ModGroups.classpathGroupsValue(
                    selectedModGroups.get()))
            })
            task.mutatedEnvironment.putAll(project.provider {
                val envKey = runDescriptor.orNull?.capabilities
                    ?.get("modGroupsEnv") ?: return@provider emptyMap()
                mapOf(envKey to ModGroups.mergeModClasses(
                    mutated.get().environment[envKey],
                    ModGroups.modClassesEntries(
                        selectedModGroups.get())
                        .joinToString(File.pathSeparator)))
            })
            task.logLevel.set(run.logLevel)
            task.loggingConfigFile.set(run.loggingConfigFile)
            transformSpec.effectiveRunTaskMutators().forEach { it.execute(task) }
            task.stdin.set(run.stdin)
            task.launcherVersion.set(
                project.provider {
                    project.version.toString().ifBlank { "2.0.0" }
                })
            // `${key}` substitution values — environment-level defaults
            // (shared with the JUnit twin's env substitution);
            // transform mutators add loader-specific keys through the
            // spec's `properties` (e.g. forge's `modules`,
            // `minecraft_classpath_file`, `source_roots`)
            task.launchProperties.putAll(envLaunchDefaults)
            task.mutatedLaunchProperties.set(mutated.map {
                it.properties.mapValues { (_, v) -> v() }
            })
            // user-declared `setProperty` keys — applied last so they
            // win over env defaults and mutator keys (1.x parity)
            task.userLaunchProperties.set(run.launchProperties)
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
            // `-Punimined.run.dryRun=true` prints the assembled launch
            // instead of exec'ing — the runs-verification surface
            task.dryRun.set(
                project.providers
                    .gradleProperty("unimined.run.dryRun")
                    .map { it.toBoolean() }
                    .orElse(false))
            // `-Punimined.run.verify=true` audits the assembled plan
            // (main class + required resources on the classpath,
            // entry/agent/asset-index existence) without exec'ing
            task.verify.set(
                project.providers
                    .gradleProperty("unimined.run.verify")
                    .map { it.toBoolean() }
                    .orElse(false))
            task.requiredJavaVersion.set(environment.requiredJavaVersion)
            task.useToolchains.set(environment.useToolchains)
            // resolved at execution — toolchain resolution (and any
            // auto-provisioning) stays out of configuration time
            // keyed on the *task* properties, not the env's — a per-run
            // `useToolchains`/`requiredJavaVersion` override must steer
            // the executable pick the same way it steers launch()'s
            // java-floor check (upstream #226: opting out of toolchains
            // must not resolve one anyway)
            task.javaExecutable.set(task.useToolchains.flatMap { use ->
                if (use) toolchains.launcherFor {
                    it.languageVersion.set(
                        task.requiredJavaVersion.map(JavaLanguageVersion::of))
                }.map { it.executablePath.asFile.absolutePath }
                else project.providers.provider {
                    File(System.getProperty("java.home"), "bin/java").absolutePath
                }
            })
        }

        val runTask = project.tasks.register(
            taskName, MinecraftRunTask::class.java, configureRunTask)

        // `genLaunchScript<Name>` — same resolved launch, rendered to
        // runnable run<Name>.sh/.cmd in the run dir (MDG
        // `createLaunchScripts` parity); `genLaunchScripts` aggregates
        val scriptTaskName =
            "genLaunchScript${run.name.replaceFirstChar(Char::uppercase)}"
        val scriptTask = project.tasks.register(
            scriptTaskName, MinecraftRunTask::class.java) { t ->
            configureRunTask.execute(t)
            t.description = "Emit launch scripts " +
                    "($taskName.sh/.cmd) for run " +
                    "'${run.name}' (env '$environmentId')"
            t.emitScripts.set(true)
            t.scriptBaseName.set(taskName)
        }
        project.tasks.named("genLaunchScripts") {
            it.dependsOn(scriptTaskName)
        }

        listOf(runTask, scriptTask).forEach { taskProvider ->
            // contributions fire outside the task-creation context —
            // they may register their own tasks (mutation guard
            // forbids task-container writes during task creation)
            transformSpec.effectiveRunTaskContributions().forEach {
                it.execute(
                    xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
                        .RunTaskContext(
                            run.name, run.prod.orElse(false).get(),
                            run.type.orElse(run.name).get(), taskProvider
                        ) { arg ->
                            // execution-lazy args ride the mutated-args
                            // channel as a provider element — stays
                            // serializable, resolves when the launch
                            // command assembles (may read task outputs)
                            taskProvider.configure { t ->
                                (t as? MinecraftRunTask)
                                    ?.mutatedJvmArgs?.add(arg)
                            }
                        })
            }
        }

        // NG `isJUnit`/`unitTest` parity: a junit-typed run on a loader
        // advertising the `gametestJunit` capability also materializes
        // a Gradle `Test` task — JUnit-platform discovery replaces the
        // normal launch; the loader's program args ride an args-file
        // exposed through `fml.junit.argsfile` and jvm args an
        // @argfile. `unitTestSources` conventions to `test`.
        val junitArgsProperty =
            runDescriptor.orNull?.capabilities?.get("gametestJunit")
        run.unitTest.convention(run.type.orElse(run.name)
            .map { junitArgsProperty != null && it == "junit" })
        // `runs.all` fires before `register`ed runs apply their
        // configure action — `type` still reads as the name then, so
        // materialized junit runs resolve through the eager channel
        val declaredType = project.extensions
            .getByType(RunsExtension::class.java)
            .runDeclaredTypes[run.name]
        if (run.unitTest.get() ||
            (junitArgsProperty != null && declaredType == "junit")) {
            val testSourceSet = project.extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME)
            if (testSourceSet != null && run.unitTestSources.isEmpty())
                run.unitTestSources.add(testSourceSet)
            val argDir = project.layout.buildDirectory.dir(
                "unimined/runArgs/$environmentId-${run.name}")
            val runProgramArgs = run.programArgs
            val runJvmArgs = run.jvmArgs
            val runSystemProperties = run.systemProperties
            val runEnvironmentVariables = run.environmentVariables
            val runLaunchProperties = run.launchProperties
            val runUnitTestSources = run.unitTestSources
            project.tasks.register(
                "test${run.name.replaceFirstChar(Char::uppercase)}",
                org.gradle.api.tasks.testing.Test::class.java) { t ->
                t.dependsOn(prepare)
                t.onlyIf { runEnabled.get() }
                t.group = "verification"
                t.description = "JUnit-platform test launch for run " +
                        "'${run.name}' (env '$environmentId')"
                t.useJUnitPlatform()
                t.workingDir(project.layout.projectDirectory.dir(
                    run.workingDirectory
                        .orElse("run/$environmentId-${run.name}").get()))
                val programArgsFile = argDir.map {
                    it.file("junit-program-args.txt") }
                val jvmArgsFile = argDir.map { it.file("junit-jvm-args.txt") }
                t.systemProperty(
                    junitArgsProperty ?: "fml.junit.argsfile",
                    programArgsFile.get().asFile.absolutePath)
                t.doFirst {
                    // late-bound so post-registration runs{} edits land
                    t.systemProperties(runSystemProperties.get())
                    // mods{} groups serialize on the JUnit launch the
                    // same as run<Name> — loom adds
                    // `fabric.classPathGroups` to gametest tasks too
                    // (CompileConfiguration parity)
                    runDescriptor.orNull?.capabilities
                        ?.get("classpathGroups")
                        ?.let { prop ->
                            t.systemProperty(prop,
                                ModGroups.classpathGroupsValue(
                                    selectedModGroups.get()))
                        }
                    // env parity with run<Name>: the mutator env
                    // (forge's own FORGE_SPEC/MOD_CLASSES base) merged
                    // with the groups' `id%%dir` entries under
                    // `modGroupsEnv`, user env last — values through
                    // the same `${key}` substitution
                    val mut = mutated.get()
                    val mutEnv = mut.environment.toMutableMap()
                    runDescriptor.orNull?.capabilities
                        ?.get("modGroupsEnv")
                        ?.let { envKey ->
                            mutEnv[envKey] = ModGroups.mergeModClasses(
                                mut.environment[envKey],
                                ModGroups.modClassesEntries(
                                    selectedModGroups.get())
                                    .joinToString(File.pathSeparator))
                        }
                    val subst = envLaunchDefaults.get() +
                        mut.properties.mapValues { (_, v) -> v() } +
                        runLaunchProperties.get()
                    t.environment(subst.resolveLaunchArgs(
                        mutEnv + runEnvironmentVariables.get()))
                    programArgsFile.get().asFile.also { f ->
                        f.parentFile.mkdirs()
                        f.writeText(
                            subst.resolveLaunchArgs(
                                mutated.get().programArgs +
                                        runProgramArgs.get())
                                .joinToString("\n"))
                    }
                    jvmArgsFile.get().asFile.writeText(
                        subst.resolveLaunchArgs(
                            mutated.get().jvmArgs +
                                    runJvmArgs.get())
                            .joinToString("\n"))
                }
                t.jvmArgs(run.javaagents.files.map {
                    "-javaagent:${it.absolutePath}" })
                // JVM-native @argfile expansion carries the rest
                t.jvmArgs("@${jvmArgsFile.get().asFile.absolutePath}")
                t.classpath = project.files(
                    sourceSet.runtimeClasspath,
                    // mods{} group outputs — the same launch-classpath
                    // contribution run<Name> gets
                    selectedModGroups.map { groups ->
                        groups.flatMap { it.launchFiles() }
                    },
                    run.additionalRuntimeClasspath,
                    project.configurations.named("${taskName}Dependencies"),
                    project.provider {
                        runUnitTestSources.map { it.output }
                    })
                t.testClassesDirs = project.files(project.provider {
                    runUnitTestSources.map { it.output.classesDirs }
                })
            }.also { testTask ->
                transformSpec.effectiveRunTaskContributions().forEach {
                    it.execute(
                        xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
                            .RunTaskContext(
                                run.name, run.prod.orElse(false).get(),
                                run.type.orElse(run.name).get(), testTask
                            ) { arg ->
                                // Test.jvmArgs stringifies elements —
                                // resolve the provider in a doFirst
                                // (execution, after writer deps ran)
                                // instead of registering it
                                testTask.configure { t ->
                                    t.doFirst { task ->
                                        (task as? org.gradle.api.tasks
                                            .testing.Test)
                                            ?.jvmArgs(arg.get())
                                    }
                                }
                            })
                }
            }
        }
    }

    /**
     * Resolvable view of this environment's native jars — native
     * classifier coordinates are computed from the effective version
     * document at resolution time (execution), so no metadata is fetched
     * during configuration.
     */
    private val LAUNCH_TOKEN = Regex("\\$\\{([^}]+)}")

    /**
     * `MinecraftRunTask`'s `${key}` substitution for the JUnit twin's
     * args/env — unknown keys collapse to `""` (they may belong to an
     * era/config the loader doesn't model).
     */
    private fun Map<String, String>.resolveLaunchArgs(
        args: List<String>
    ): List<String> = args.map { arg ->
        val subbed = entries.fold(arg) { a, (k, v) ->
            a.replace("\${$k}", v) }
        subbed.replace(LAUNCH_TOKEN, "")
    }

    private fun Map<String, String>.resolveLaunchArgs(
        values: Map<String, String>
    ): Map<String, String> =
        values.mapValues { (_, v) ->
            resolveLaunchArgs(listOf(v)).single() }

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
                // `lwjgl3 {}` swap — host natives under the lwjgl3
                // classifier scheme (the lwjgl2 entries already
                // dropped by the shared coord-filter pipeline)
                if (environment.lwjgl3.enabled.get()) {
                    environment.lwjgl3.nativesCoords(
                        environment.lwjgl3.nativesClassifier()
                    ).forEach { deps.add(dependencies.create(it)) }
                }
            }
        }
        return project.files(configuration)
    }

}
