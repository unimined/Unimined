package xyz.wagyourtail.unimined.transform.bukkit.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.time.Duration

/**
 * Environment wiring for bukkit-family products — every product
 * resolves through the descriptor registry and contributes its
 * production-namespace convention; the *server artifact* strategy
 * (`serverArtifact` capability) decides how the patched jar
 * materializes:
 *
 *  - `distPatch` (paper) — dist-endpoint download + embedded-patch /
 *    bundler-extraction step on top of the provider's vanilla server
 *    jar (slices 2–3).
 *  - `provider` (craftbukkit/spigot) — a non-native `provider-*`
 *    module supplies the spigot-namespace server jar directly.
 *
 * Both land on the same raw→dev artifact view, so the user's chosen
 * dev namespace remaps through the standard pipeline.
 */
internal object BukkitEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val descriptor = environment.loaderSpec
            .map { spec ->
                LoaderRegistry.resolveBuiltin(spec.name)
                    ?.takeIf { it.family == "bukkit" }
            }
        // server-side environments: provider stays the user's choice,
        // but the production mod-source convention comes from the
        // descriptor (spigot namespace for all bukkit products)
        environment.modSourceNamespaces.convention(
            descriptor.map { d ->
                if (d != null) listOf("spigot", d.prodNamespace) else emptyList()
            }
        )
        // bukkit products only exist server-side — the raw artifact is
        // the mojang server jar (which the paper dist patch also
        // expects), never a merged jar. Convention only: an explicit
        // `side =` in the DSL still wins. `orNull` (not `map`) keeps
        // the provider present when the loader resolves non-bukkit —
        // a mapped provider goes absent on null and `side.get()`
        // would fail for vanilla/other-family envs.
        environment.side.convention(
            project.provider {
                val d = environment.loaderSpec.orNull
                    ?.let { LoaderRegistry.resolveBuiltin(it.name) }
                    ?.takeIf { it.family == "bukkit" }
                if (d != null) xyz.wagyourtail.unimined.api.spec.Side.SERVER
                else xyz.wagyourtail.unimined.api.spec.Side.MERGED
            }
        )
        // bound fires before the DSL's `loader(...)` lands — the
        // contributions inside check loaderSpec, so defer to
        // post-evaluation (or run now when already evaluated)
        project.afterDslEval {
            contributeServerProcessors(it, environment)
            contributeRunConfig(it, environment)
        }
    }

    /**
     * Server-run surface for every bukkit env: the patched server
     * jar's entry point is `org.bukkit.craftbukkit.Main`, and the
     * ClasspathPluginLoader agent maps the env's source-set output
     * onto the classpath as plugin groups (mirrors 1.x
     * `applyServerRunTransform`). The CPL agent resolves through a
     * `classpathPluginLoader<env>` configuration defaulting to
     * `0.1.0-SNAPSHOT`; users may override the dependency.
     */
    /**
     * API-only mode — compiles against the descriptor's published api
     * artifact (`<g>:<a>:<mc>-R0.1-SNAPSHOT`, spigot/paper convention)
     * instead of producing a patched server jar. Still carries the
     * production namespace so reobf targets stay consistent.
     */
    private fun contributeApiArtifact(
        project: Project,
        environment: MinecraftEnvironment,
        descriptor: xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
    ) {
        val api = descriptor.capabilities["apiArtifact"] ?: return
        val envSourceSet = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName(environment.environmentId)
        project.configurations.named(envSourceSet.compileClasspathConfigurationName) { conf ->
            conf.withDependencies { deps ->
                deps.add(project.dependencies.create(
                    "$api:${environment.version.get()}-R0.1-SNAPSHOT"))
            }
        }
    }

    private fun contributeRunConfig(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        if (!environment.loaderSpec.isPresent) return
        val loader = environment.loaderSpec.get()
        val descriptor = LoaderRegistry.resolveBuiltin(loader.name)
        if (descriptor?.family != "bukkit") return

        val envId = environment.environmentId
        val cplConfig = project.configurations.maybeCreate(
            if (envId == "main") "classpathPluginLoader"
            else "classpathPluginLoader${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        cplConfig.defaultDependencies { deps ->
            deps.add(project.dependencies.create(
                "xyz.wagyourtail.unimined:classpath-plugin-loader:0.1.0-SNAPSHOT:all"
            ).also { (it as org.gradle.api.artifacts.ExternalDependency).isTransitive = false })
        }

        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(envId)
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
        // `plugins/` staging is the default mod-discovery path — the
        // dev jar lands in the run dir's `plugins/` and loads through
        // the server's own plugin manager, covering plugin.yml AND
        // paper-plugin.yml (which CPL's classpath scan can't read).
        // `features { modDiscovery("classpath") }` opts back into the
        // CPL java agent + pluginGroups classpath scan instead.
        val stagePlugins = envSpec.features.bindings["modDiscovery"] != "classpath"

        envSpec.runMutators += org.gradle.api.Action { config ->
            config.mainClass = "org.bukkit.craftbukkit.Main"
            if (!stagePlugins) {
                val jar = cplConfig.resolve().first { it.extension == "jar" }
                config.javaagents.add(jar.toPath())
                val out = sourceSets.getByName(envId).output
                val paths = (out.classesDirs.files + listOfNotNull(out.resourcesDir))
                    .joinToString(java.io.File.pathSeparator) { it.absolutePath }
                config.jvmArgs += "-Dcpl.pluginGroups=$paths"
            }
        }
        envSpec.runTaskMutators += org.gradle.api.Action { task ->
            // paperclip order: the patched server jar precedes the
            // libraries it embeds patched copies of (com.mojang:logging
            // & co.) — libraries-first resolution would shadow them
            // with unmoj'd upstream jars. unimined-runs isn't on this
            // module's classpath, so the task type is reached via the
            // task's runtimeClasspath property
            val classpath = task.javaClass.getMethod("getRuntimeClasspath")
                .invoke(task) as? org.gradle.api.file.ConfigurableFileCollection
            if (stagePlugins) {
                // the staged jar provides plugin classes via the
                // plugin classloader — env output on the app classpath
                // would parent-load first and trip JavaPlugin's
                // "valid classloader" check
                classpath?.setFrom(
                    project.configurations.named(environment.minecraftDevViewName),
                    // deps only — the output dirs would let plugin
                    // classes load from the app classpath instead of
                    // the plugin classloader
                    project.configurations.named(
                        sourceSets.getByName(envId).runtimeClasspathConfigurationName),
                    // provider file deps (bukkit-family embedded
                    // libraries — api jar + runtime libs) ride the
                    // sourceSet's file collection, not the
                    // configuration, so they need a direct source here
                    project.files(project.provider {
                        project.extensions.getByType(
                            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java
                        ).artifacts(environment.environmentId).get().extraDependencies
                    })
                )
            } else {
                classpath?.setFrom(
                    project.configurations.named(environment.minecraftDevViewName),
                    sourceSets.getByName(envId).runtimeClasspath
                )
            }
        }

        // stage the dev jar into `plugins/` — duck-typed via
        // `property()` since transform modules don't depend on runs
        if (stagePlugins) {
            envSpec.prepareTaskMutators += org.gradle.api.Action { task ->
                @Suppress("UNCHECKED_CAST")
                (task.property("stageInto") as?
                        org.gradle.api.provider.Property<String>)?.set("plugins")
            }
        }
    }

    /**
     * Paper-era `distPatch` products patch the vanilla server jar
     * inside the jar-processors pipeline — the embedded binary patch
     * rides the serialized spec so the step is cache-keyed on the
     * dist build, not the download itself.
     */
    private fun contributeServerProcessors(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        if (!environment.loaderSpec.isPresent) return
        val loader = environment.loaderSpec.get()
        val descriptor = LoaderRegistry.resolveBuiltin(loader.name)
            ?.takeIf { it.family == "bukkit" } ?: return
        if (environment.apiOnly.orElse(false).get()) {
            contributeApiArtifact(project, environment, descriptor)
            return
        }
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(environment.environmentId)
        val downloader = Downloader(
            cacheDir = project.layout.projectDirectory
                .dir(".gradle/unimined/paper-cache").asFile.toPath(),
            offline = project.gradle.startParameter.isOffline,
            refreshCache = project.gradle.startParameter.isRefreshDependencies ||
                project.findProperty("unimined.forceReload") == "true" ||
                project.findProperty("unimined.refreshDownloads") == "true",
            metadataTtl = Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                project.logger.warn(
                    "[Unimined/Cache] Using cached $file after refresh failed: ${error.message}"
                )
            }
        )

        val buildData = SpigotBuildData(downloader)
        if (descriptor.capabilities["serverArtifact"] == "provider") {
            // BuildTools products ≥1.17 already carry mojmap names —
            // inheriting the vanilla spec's 'official' source namespace
            // makes the uml guard read the jar as an already-remapped
            // artifact (spigot's offHits=0, craftbukkit slipped through
            // on a single coincidental probe hit). Pre-1.17 BuildTools
            // jars are spigotProd-obf instead.
            environment.devRemapSourceNamespace.set(
                if (xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
                        .compare(environment.version.get(), "1.17") >= 0
                ) "mojmap" else "spigotProd"
            )
            envSpec.jarProcessors += SpigotNmsRelocationProcessor {
                buildData.versionPackage(environment.version.get())
            }
        }
        if (descriptor.capabilities["serverArtifact"] != "distPatch") return
        val distJar = {
            // loader.version is the paper-api *maven* version; the
            // dist endpoint keys builds by number (or "latest") — a
            // maven-style version means "latest build for this MC
            // version" since the two surfaces can't share one string
            val build = loader.version.takeIf {
                it == "latest" || it.toIntOrNull() != null
            } ?: "latest".also {
                project.logger.info(
                    "[Unimined/Paper] '${loader.version}' is a paper-api " +
                            "maven version, not a dist build id — using " +
                            "the latest build for " +
                            "${environment.version.get()}. Pass a build " +
                            "number to pin the server jar."
                )
            }
            PaperDistResolver.serverJar(
                downloader, environment.version.get(), build
            )
        }
        envSpec.jarProcessors += PaperPatchProcessor(distJar) {
            // paper >=1.20.5 ships mojang-mapped servers; older dists
            // patch into the spigot-obf runtime namespace
            if (xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
                    .compare(environment.version.get(), "1.20.5") >= 0
            ) "mojmap" else "spigotProd"
        }

        // bundler-era classpaths: the dist jar's libraries.list holds
        // the maven coords paperclip resolves at launch — including the
        // bukkit api itself (paper-mojangapi), so both compile and
        // runtime views extend from them
        val paperLibs = project.configurations.create("paperLibraries${
            environment.environmentId.replaceFirstChar { it.uppercaseChar() }}")
        paperLibs.withDependencies { deps ->
            // embedded jars (paper's own snapshot libs — not on public
            // mavens) extract to the project cache as file deps; the rest
            // resolve as ordinary maven coords
            PaperBundlerLibraries.extractEmbedded(
                distJar(), downloader.cacheDir.resolve("bundler-libs")
            ).forEach { (entry, file) ->
                deps.add(project.dependencies.create(
                    file?.let { project.files(it) } ?: entry.coord
                ))
            }
        }
        val envSourceSet = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName(environment.environmentId)
        project.configurations.named(envSourceSet.runtimeClasspathConfigurationName) { conf ->
            conf.extendsFrom(paperLibs)
        }
        project.configurations.named(envSourceSet.compileClasspathConfigurationName) { conf ->
            conf.extendsFrom(paperLibs)
        }
    }

}
