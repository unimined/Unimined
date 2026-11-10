package xyz.wagyourtail.unimined.ide.internal

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.ide.ClasspathEntry
import xyz.wagyourtail.unimined.ide.ClasspathGroup
import xyz.wagyourtail.unimined.ide.IdeClasspathModel
import xyz.wagyourtail.unimined.ide.IdeRunModel
import xyz.wagyourtail.unimined.mclauncher.LaunchEnvironment
import xyz.wagyourtail.unimined.mclauncher.LaunchResolver
import xyz.wagyourtail.unimined.mclauncher.OfflineAuth
import xyz.wagyourtail.unimined.mclauncher.VersionChain
import xyz.wagyourtail.unimined.mclauncher.VersionData
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.RunConfig
import xyz.wagyourtail.unimined.runs.internal.MutableRunConfigSpec
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Resolves the Gradle-free [IdeRunModel]/[IdeClasspathModel] from
 * the environment's live spec — the SAME lazy resolution
 * `RunsWiring` uses for `run<Name>` (provider launch metadata +
 * `MutableRunConfigSpec` filled by transform mutators). Everything
 * stays inside `project.provider { }` so IDE tasks resolve at
 * execution time only and remain configuration-cache safe.
 */
internal object IdeModelResolver {

    /** Mutable run spec with transform mutators applied — identical
     *  to the `mutated` provider in `RunsWiring.registerRunTask`. */
    private fun mutatedSpec(
        project: Project,
        transform: TransformExtension,
        environmentId: String,
        run: RunConfig
    ): Provider<MutableRunConfigSpec> = project.provider {
        MutableRunConfigSpec(run.name, run.prod.get()).also { spec ->
            transform.sourceSet(environmentId)
                .effectiveRunMutators().forEach { it.execute(spec) }
        }
    }

    /** One run → [IdeRunModel]. Classpath mirrors `run<Name>`: dev =
     *  `sourceSet.runtimeClasspath`; prod = raw MC view + declared
     *  libraries + `*Direct` mod views + remapJar output. */
    fun resolveRun(
        project: Project,
        environment: MinecraftEnvironment,
        run: RunConfig,
        sourceSet: SourceSet,
        minecraft: MinecraftExtension,
        transform: TransformExtension
    ): Provider<IdeRunModel> {
        val environmentId = environment.environmentId
        val mutated = mutatedSpec(project, transform, environmentId, run)

        // group tag each classpath file by which known collection it
        // resolves from — loader jars & provider extras land in the
        // residual bucket, displayed under LOADER
        return project.provider {
            val files: Set<java.io.File> = if (run.prod.get()) {
                project.configurations
                    .findByName(environment.minecraftRawViewName)
                    ?.files.orEmpty() +
                    project.configurations
                        .findByName(environment
                            .minecraftLibrariesConfigurationName)
                        ?.files.orEmpty() +
                    listOfNotNull(
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Direct"),
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Direct")
                    ).flatMap { it.files } +
                    remapJarFiles(project, environmentId)
            } else {
                sourceSet.runtimeClasspath.files
            }
            // mirror `run<Name>` execution: merge the version
            // document through `LaunchResolver` — spec args layer on
            // top of the plan's, game args are client-only, and the
            // monolithic launchwrapper-era ASM jars drop when a
            // modern `asm-<v>` artifact is present
            val deduped = dedupeAsm(files.toList())
            val mut = mutated.get()
            val artifacts = minecraft.artifacts(environmentId).get()
            val metadata = artifacts.launch
                ?: error("env '$environmentId' exposes no" +
                        " launch metadata")
            val document = VersionData.parse(VersionChain.resolve(
                metadata.versionJson(metadata.rootId),
                metadata::versionJson))
            val workDir = run.workingDirectory.orNull
                ?: "run/$environmentId-${run.name}"
            val runDir = project.layout.projectDirectory
                .dir(workDir).asFile
            val nativesDir = project.layout.buildDirectory
                .dir("unimined/natives/$environmentId").get().asFile
            val assetsRoot = UniminedCache.global(project)
                .resolve("assets").toFile()
            val assetIndexId = artifacts.assets.orNull?.id
            val plan = LaunchResolver.resolve(document,
                LaunchEnvironment(
                    classpath = deduped.map { it.toPath() },
                    gameDir = runDir.toPath(),
                    assetsDir = assetsRoot.toPath(),
                    assetIndexId = assetIndexId,
                    nativesDir = nativesDir.toPath(),
                    session = OfflineAuth(
                        run.username.orNull ?: "Dev").session(),
                    features = run.features.getOrElse(emptySet()),
                    launcherVersion = project.version.toString()
                        .ifBlank { "2.0.0" },
                    resolutionWidth = run.width.orNull,
                    resolutionHeight = run.height.orNull,
                    extraJvmArgs = betacraftProxyArgs(
                        project, document.id),
                ))
            // `${key}` substitution values — env-level defaults as
            // wired by RunsWiring, plus transform mutator properties
            val subst = mapOf(
                "version_name" to
                    (environment.version.orNull ?: "unspecified"),
                "version_type" to "release",
                "assets_root" to assetsRoot.absolutePath,
                "assets_index" to (assetIndexId ?: ""),
                "assets_index_name" to (assetIndexId ?: ""),
                "asset_index" to (assetIndexId ?: ""),
                "game_assets" to
                    runDir.resolve("resources").absolutePath,
                "game_dir" to runDir.absolutePath,
                "game_directory" to runDir.absolutePath,
                "natives_root" to nativesDir.absolutePath,
                "natives_directory" to nativesDir.absolutePath,
                "library_directory" to
                    runDir.resolve("libraries").absolutePath,
            ) + mut.properties.mapValues { (_, v) -> v() }
            IdeRunModel(
                name = run.name,
                environmentId = environmentId,
                side = run.side.get().name,
                prod = run.prod.get(),
                mainClass = mut.mainClass ?: run.mainClass.orNull
                    ?: if (run.side.get().name == "CLIENT")
                        plan.mainClass
                    else serverMainClass(document),
                jvmArgs = subst.resolveLaunchArgs(
                    plan.jvmArgs +
                        "-Djava.library.path=${nativesDir.absolutePath}" +
                        run.jvmArgs.get() + mut.jvmArgs),
                // the version document's `game` args are client-only
                programArgs = subst.resolveLaunchArgs(
                    (if (run.side.get().name == "SERVER")
                        emptyList() else plan.programArgs) +
                        run.programArgs.get() + mut.programArgs),
                environmentVariables = plan.environment +
                    subst.resolveLaunchArgs(
                        mut.environment +
                            run.environmentVariables.get()),
                workingDirectory = workDir,
                classpath = deduped.map { f ->
                    ClasspathEntry(
                        jar = f.absolutePath,
                        group = groupOf(project, environment, f),
                    )
                }
            )
        }
    }

    /** Per-environment classpath groups for IDE sync. */
    fun resolveClasspath(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
    ): Provider<IdeClasspathModel> {
        val environmentId = environment.environmentId
        return project.provider {
            val groups = ClasspathGroup.values().associateWith { g ->
                when (g) {
                    ClasspathGroup.MINECRAFT -> project.configurations
                        .findByName(environment.minecraftDevViewName)
                        ?.files.orEmpty()
                    ClasspathGroup.LIBRARIES -> project.configurations
                        .findByName(environment
                            .minecraftLibrariesConfigurationName)
                        ?.files.orEmpty()
                    ClasspathGroup.MODS -> listOfNotNull(
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Remapped"),
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Remapped")
                    ).flatMap { it.files }.toSet()
                    // loader jars + provider file collections =
                    // the env runtime classpath minus the groups
                    // already claimed
                    ClasspathGroup.LOADER -> sourceSet.runtimeClasspath
                        .files - project.configurations
                        .findByName(environment.minecraftDevViewName)
                        ?.files.orEmpty() - project.configurations
                        .findByName(environment
                            .minecraftLibrariesConfigurationName)
                        ?.files.orEmpty() - listOfNotNull(
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Remapped"),
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Remapped")
                    ).flatMap { it.files }.toSet() -
                            sourceSet.output.files
                    ClasspathGroup.PROJECT -> sourceSet.output.files
                }.map { ClasspathEntry(jar = it.absolutePath, group = g) }
            }
            IdeClasspathModel(
                environmentId = environmentId,
                groups = groups,
                excludedDirectories = listOf("run")
            )
        }
    }

    /** Files of the env's remapJar task outputs (prod classpath). */
    private fun remapJarFiles(
        project: Project, environmentId: String
    ): Set<java.io.File> {
        val name = if (environmentId == SourceSet.MAIN_SOURCE_SET_NAME)
            "remapJar"
        else "remap${environmentId.replaceFirstChar(Char::uppercase)}Jar"
        return project.tasks.findByName(name)?.outputs?.files?.files
            ?: emptySet()
    }

    /** Which [ClasspathGroup] a file belongs to on the env
     *  classpath — checked against the known view configurations. */
    private fun groupOf(
        project: Project,
        environment: MinecraftEnvironment,
        file: java.io.File
    ): ClasspathGroup {
        fun inConfig(name: String) =
            project.configurations.findByName(name)
                ?.files?.contains(file) == true
        return when {
            inConfig(environment.minecraftRawViewName) ||
                    inConfig(environment.minecraftDevViewName) ->
                ClasspathGroup.MINECRAFT
            inConfig(environment.minecraftLibrariesConfigurationName) ->
                ClasspathGroup.LIBRARIES
            inConfig(
                "${environment.modImplementationConfigurationName}Direct") ||
                    inConfig(
                        "${environment.modRuntimeOnlyConfigurationName}Direct") ||
                    inConfig(
                        "${environment.modImplementationConfigurationName}Remapped") ||
                    inConfig(
                        "${environment.modRuntimeOnlyConfigurationName}Remapped") ->
                ClasspathGroup.MODS
            else -> ClasspathGroup.LOADER
        }
    }

    /** `MinecraftRunTask.defaultMainClass` — server runs get no
     *  `mainClass` from the version document; fall back through
     *  the era's server entrypoints. */
    private fun serverMainClass(document: VersionData): String =
        if ((document.javaMajorVersion ?: 8) >= 17)
            "net.minecraft.server.Main"
        else "net.minecraft.server.MinecraftServer"

    /** `MinecraftRunTask`'s monolithic-ASM dedupe — `asm-all` /
     *  `asm-debug-all` conflict with modular `asm` on
     *  loader-managed classpaths; drop only when a modern
     *  `asm-<v>.jar` is present. */
    private fun dedupeAsm(files: List<java.io.File>) =
        if (files.any { it.name.matches(
                Regex("""asm-\d.*\.jar""")) })
            files.filterNot {
                it.name.startsWith("asm-all-") ||
                        it.name.startsWith("asm-debug-all-") }
        else files

    /** Beta/alpha-era betacraft proxy args — the task downloads
     *  `<version>.info` into `.unimined/betacraft.info`; the IDE
     *  pass only reads the local file, never the network. */
    private fun betacraftProxyArgs(
        project: Project, versionId: String?
    ): List<String> = runCatching {
        java.util.Properties().also {
            project.rootDir.resolve(".unimined/betacraft.info")
                .inputStream().use(it::load)
        }.getProperty("proxy-args")?.split(" ") ?: emptyList()
    }.getOrElse { emptyList() }

    private val LAUNCH_TOKEN = Regex("\\$\\{([^}]+)}")

    /** `MinecraftRunTask.resolveLaunchArgs` — `${key}` substitution
     *  over spec args; unknown keys collapse to empty. */
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
}
