package xyz.wagyourtail.unimined.transform.bukkit.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.provider.native.download.Downloader
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
        contributeServerProcessors(project, environment)
        contributeRunConfig(project, environment)
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
        if (LoaderRegistry.resolveBuiltin(loader.name)?.family != "bukkit") return

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
        envSpec.runMutators += org.gradle.api.Action { config ->
            config.mainClass = "org.bukkit.craftbukkit.Main"
            val jar = cplConfig.resolve().first { it.extension == "jar" }
            config.jvmArgs += "-javaagent:${jar.absolutePath}"
            val out = sourceSets.getByName(envId).output
            val paths = (out.classesDirs.files + listOfNotNull(out.resourcesDir))
                .joinToString(java.io.File.pathSeparator) { it.absolutePath }
            config.jvmArgs += "-Dcpl.pluginGroups=$paths"
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
        if (descriptor.capabilities["serverArtifact"] == "provider" &&
            buildData.supportsVersionedNms(environment.version.get())) {
            envSpec.jarProcessors += SpigotNmsRelocationProcessor {
                buildData.versionPackage(environment.version.get())
            }
        }
        if (descriptor.capabilities["serverArtifact"] != "distPatch") return
        val distJar = {
            PaperDistResolver.serverJar(
                downloader, environment.version.get(), loader.version
            )
        }
        envSpec.jarProcessors += PaperPatchProcessor(distJar)

        // bundler-era runtime classpath: the dist jar's libraries.list
        // holds the maven coords paperclip resolves at launch —
        // surfaced as a resolution-time extendsFrom so Gradle manages
        // them like any other dependency
        val paperLibs = project.configurations.create("paperLibraries${
            environment.environmentId.replaceFirstChar { it.uppercaseChar() }}")
        paperLibs.withDependencies { deps ->
            PaperBundlerLibraries.coords(distJar()).forEach {
                deps.add(project.dependencies.create(it))
            }
        }
        val envSourceSet = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName(environment.environmentId)
        project.configurations.named(envSourceSet.runtimeClasspathConfigurationName) { conf ->
            conf.extendsFrom(paperLibs)
        }
    }

}
