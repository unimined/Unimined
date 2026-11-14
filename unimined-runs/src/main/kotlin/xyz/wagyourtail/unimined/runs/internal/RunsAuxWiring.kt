package xyz.wagyourtail.unimined.runs.internal

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.RunSide
import xyz.wagyourtail.unimined.runs.RunsExtension

/**
 * Aux-environment run wiring — datagen/gametest envs get their runs
 * and junit deps contributed here, driven off the loader descriptor's
 * `datagen*`/`gametest*` capabilities. Extracted from [RunsWiring]:
 * the aux-env pass is a distinct concept from the main run-task
 * registration.
 */
internal object RunsAuxWiring {

    /** Datagen → a `run<Data>` config bound to the child env with the
     *  parent loader's `datagenMain`/`datagenArgs` rows; gametest → the
     *  loader's junit artifact + `useJUnitPlatform()` on the child's
     *  test task (orchestrated in-jvm launches, not run configs). */
    fun wireAuxEnvs(
        project: Project,
        environment: MinecraftEnvironment,
        minecraft: MinecraftExtension,
        extension: RunsExtension
    ) {
        val registry =
            xyz.wagyourtail.unimined.transform.loader.LoaderRegistry()
        val descriptor = environment.loaderSpec.orNull
            ?.let { registry.resolve(it.name) }
        for (dataEnv in environment.datagenEnvs) {
            val runName = dataEnv.environmentId
            if (extension.runs.findByName(runName) != null) continue
            // eager env binding — the run registers during afterEvaluate,
            // where run.environment's convention may not be visible to
            // other envs' runs.all callbacks yet (same reason the
            // auto client/server runs record runEnvironments eagerly)
            extension.runEnvironments[runName] = dataEnv.environmentId
            if (descriptor?.capabilities?.get("datagenMain") == null
                && descriptor?.capabilities?.get("datagenArgs") == null
                && descriptor?.capabilities?.get("datagenJvmArgs") == null) {
                project.logger.warn(
                    "[unimined] env '${dataEnv.environmentId}' declares " +
                    "datagen() but loader " +
                    "'${environment.loaderSpec.orNull?.name}' has no " +
                    "datagenMain capability — run$runName will launch " +
                    "the plain game"
                )
            }
            extension.runs.register(runName) { run ->
                run.environment.convention(dataEnv.environmentId)
                run.side.convention(RunSide.CLIENT)
                descriptor?.capabilities?.get("datagenMain")
                    ?.let { run.mainClass.convention(it) }
                descriptor?.capabilities?.get("datagenArgs")?.let { tpl ->
                    tpl.split(" ").forEach { arg ->
                        val resolved = arg.replace("{output}",
                            "src/${environment.environmentId}/generated")
                        if ("{modid}" in resolved) {
                            run.programArgs.add(
                                modIdArg(project, environment.environmentId, resolved))
                        } else {
                            run.programArgs.add(resolved)
                        }
                    }
                }
                descriptor?.capabilities?.get("datagenJvmArgs")?.let { tpl ->
                    tpl.split(" ").forEach { arg ->
                        val resolved = arg.replace("{output}",
                            "src/${environment.environmentId}/generated")
                        if ("{modid}" in resolved) {
                            run.jvmArgs.add(
                                modIdArg(project, environment.environmentId, resolved))
                        } else {
                            run.jvmArgs.add(resolved)
                        }
                    }
                }
                if (dataEnv.clientDatagen) {
                    descriptor?.capabilities?.get("datagenClientArg")
                        ?.let { arg ->
                            run.programArgs.set(
                                run.programArgs.get() + arg)
                        }
                }
            }
        }
        for (testEnv in environment.gametestEnvs) {
            if (descriptor?.capabilities?.get("gametestJunit") == null) {
                project.logger.warn(
                    "[unimined] env '${testEnv.environmentId}' declares " +
                    "gametest() but loader " +
                    "'${environment.loaderSpec.orNull?.name}' has no " +
                    "gametestJunit capability — no junit dep is added"
                )
            }
            descriptor?.capabilities?.get("gametestJunit")?.let { tpl ->
                val coord = tpl.replace("{loader}",
                    environment.loaderSpec.orNull?.version ?: "")
                project.dependencies.add(
                    "testImplementation",
                    project.dependencies.create(coord))
                // the junit surface: the loader's knot session listener
                // boots the game provider off `java.class.path` when the
                // junit session opens, so `test` needs the gametest
                // env's full runtimeClasspath (dev MC + loader + mods +
                // the parent's fmj). Feeding the `test` source set
                // (loom semantics — src/test code can touch MC) covers
                // both compile and run; provider-of-filecollection keeps
                // it lazy and CC-serializable.
                val sourceSets = project.extensions.getByType(
                    org.gradle.api.tasks.SourceSetContainer::class.java)
                val gametestSourceSet = sourceSets.named(
                    testEnv.environmentId)
                sourceSets.named("test") { ts ->
                    ts.compileClasspath += project.files(
                        gametestSourceSet.map { it.runtimeClasspath })
                    ts.runtimeClasspath += project.files(
                        gametestSourceSet.map { it.runtimeClasspath })
                }
                project.tasks.named("test",
                    org.gradle.api.tasks.testing.Test::class.java) { t ->
                    // gametest classes are scanned as tests too —
                    // Test exposes set-only FileCollections
                    t.testClassesDirs = t.testClassesDirs + project.files(
                        gametestSourceSet.map { it.output.classesDirs })
                    t.dependsOn(
                        gametestSourceSet.map { it.classesTaskName })
                }
            }
            // client gametests: arm the gametest env's auto `<env>Client`
            // run (bound to the gametest env — its classpath carries the
            // parent's classes via combineWith plus its own). `matching`
            // stays live: the env's own wire pass may register the run
            // after this one.
            descriptor?.capabilities?.get("gametestClientJvmArgs")?.let { tpl ->
                extension.runs
                    .matching { it.name == "${testEnv.environmentId}Client" }
                    .configureEach { run ->
                        tpl.split(" ").forEach { arg ->
                            if ("{modid}" in arg) {
                                run.jvmArgs.add(
                                    modIdArg(project, environment.environmentId, arg))
                            } else {
                                run.jvmArgs.add(arg)
                            }
                        }
                    }
            }
            // server gametests: same shape on `<env>Server` —
            // fabric-api's gametest module runs `fabric-gametest`
            // entrypoints in-process under -Dfabric-api.gametest and
            // emits junit xml to report-file
            descriptor?.capabilities?.get("gametestServerJvmArgs")?.let { tpl ->
                extension.runs
                    .matching { it.name == "${testEnv.environmentId}Server" }
                    .configureEach { run ->
                        val reportFile = project.layout.buildDirectory.file(
                            "reports/gametest/" +
                                "${testEnv.environmentId}-${run.name}.xml"
                        ).get().asFile.absolutePath
                        tpl.split(" ").forEach { arg ->
                            when {
                                "{modid}" in arg -> run.jvmArgs.add(
                                    modIdArg(project, environment.environmentId, arg))
                                "{reportFile}" in arg -> run.jvmArgs.add(
                                    arg.replace("{reportFile}", reportFile))
                                else -> run.jvmArgs.add(arg)
                            }
                        }
                    }
            }
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .configureEach { it.useJUnitPlatform() }
        }
    }

    /**
     * Resolve `{modid}` in a capability arg template against the mod id
     * unimined-mod publishes per environment (`unimined.modId.<env>`
     * extra property — `mod.id`, else a `*.mod.json` resource's `id`),
     * falling back to `project.name`. Lazy so DSL configuration order
     * doesn't matter; keep the key prefix in sync with
     * ModPackagingWiring.
     */
    private fun modIdArg(
        project: Project,
        environmentId: String,
        arg: String
    ) = project.provider {
        val extra = project.extensions.getByType(
            org.gradle.api.plugins.ExtraPropertiesExtension::class.java)
        val key = "unimined.modId.$environmentId"
        val published = if (extra.has(key)) extra.get(key) as? Provider<*> else null
        arg.replace("{modid}", (published?.get() as? String) ?: project.name)
    }
}
