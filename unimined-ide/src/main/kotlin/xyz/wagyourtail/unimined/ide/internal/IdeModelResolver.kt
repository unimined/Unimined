package xyz.wagyourtail.unimined.ide.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.failProblem
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
import xyz.wagyourtail.unimined.minecraft.ModGroups
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
            spec.type = run.type.orElse(run.name).get()
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
            val mut = mutated.get()
            // mods{} declared groups — dev-launch parity with
            // run<Name>: the selection joins the launch classpath and
            // serializes onto the loader's mod-grouping channel
            // (`classpathGroups` sysprop / `modGroupsEnv` env var —
            // loom's DLI emit adds fabric.classPathGroups to
            // IDE-launched configs the same way)
            val modGroups = ModGroups.selected(
                environment, run.loadedMods.orNull.orEmpty(),
                run.prod.get())
            val modGroupFiles = modGroups.flatMap { it.launchFiles() }
            val descriptor = environment.loaderSpec.orNull?.let {
                xyz.wagyourtail.unimined.transform.loader.LoaderRegistry()
                    .resolve(it.name)
            }
            val files: Set<java.io.File> = (if (run.prod.get()) {
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
                    remapJarFiles(project, minecraft, environmentId)
            } else {
                sourceSet.runtimeClasspath.files
            }) + modGroupFiles
            // mirror `run<Name>` execution: merge the version
            // document through `LaunchResolver` — spec args layer on
            // top of the plan's, game args are client-only, and the
            // monolithic launchwrapper-era ASM jars drop when a
            // modern `asm-<v>` artifact is present
            val deduped = dedupeAsm(files.toList())
            val artifacts = minecraft.artifacts(environmentId).get()
            val metadata = artifacts.launch
                ?: project.failProblem("ide/launch-metadata",
                    "unimined: env '$environmentId' exposes no launch " +
                        "metadata")
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
            val mutEnv = mut.environment.toMutableMap()
            descriptor?.capabilities?.get("modGroupsEnv")?.let { envKey ->
                mutEnv[envKey] = ModGroups.mergeModClasses(
                    mut.environment[envKey],
                    ModGroups.modClassesEntries(modGroups)
                        .joinToString(java.io.File.pathSeparator))
            }
            val systemPropertyArgs =
                run.systemProperties.get().map { (k, v) -> "-D$k=$v" } +
                    listOfNotNull(
                        descriptor?.capabilities?.get("classpathGroups")
                            ?.let { prop ->
                                "-D$prop=${
                                    ModGroups.classpathGroupsValue(modGroups)
                                }"
                            })
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
            // user `setProperty` keys win over env defaults and
            // mutator keys — same precedence as run<Name>
            ) + mut.properties.mapValues { (_, v) -> v() } +
                run.launchProperties.get()
            IdeRunModel(
                name = run.ideName.orElse(run.name).get(),
                folderName = run.ideFolderName.orNull,
                singleInstance = run.singleInstance.getOrElse(false),
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
                        systemPropertyArgs +
                        run.jvmArgs.get() + mut.jvmArgs),
                // the version document's `game` args are client-only
                programArgs = subst.resolveLaunchArgs(
                    (if (run.side.get().name == "SERVER")
                        emptyList() else plan.programArgs) +
                        run.programArgs.get() + mut.programArgs),
                environmentVariables = plan.environment +
                    subst.resolveLaunchArgs(
                        mutEnv +
                            run.environmentVariables.get()),
                workingDirectory = workDir,
                classpath = deduped.map { f ->
                    ClasspathEntry(
                        jar = f.absolutePath,
                        // mods{} group files are declared mod content —
                        // they render in the IDE's mod bucket like the
                        // dep views' jars
                        group = if (f in modGroupFiles) ClasspathGroup.MODS
                            else groupOf(project, environment, f),
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
            // mod entries resolved once — the MODS group consumes the
            // paired entries, LOADER subtraction their jar set
            val modEntries = modEntries(project, environment, ClasspathGroup.MODS)
            val modJars = modEntries.mapTo(HashSet()) { java.io.File(it.jar) }
            val groups = ClasspathGroup.values().associateWith { g ->
                val files = when (g) {
                    ClasspathGroup.MINECRAFT -> project.configurations
                        .findByName(environment.minecraftDevViewName)
                        ?.files.orEmpty()
                    ClasspathGroup.LIBRARIES -> project.configurations
                        .findByName(environment
                            .minecraftLibrariesConfigurationName)
                        ?.files.orEmpty()
                    // mod jars are paired with their sources — entries
                    // produced directly, not through the file map
                    ClasspathGroup.MODS -> null
                    // loader jars + provider file collections =
                    // the env runtime classpath minus the groups
                    // already claimed
                    ClasspathGroup.LOADER -> sourceSet.runtimeClasspath
                        .files - project.configurations
                        .findByName(environment.minecraftDevViewName)
                        ?.files.orEmpty() - project.configurations
                        .findByName(environment
                            .minecraftLibrariesConfigurationName)
                        ?.files.orEmpty() - modJars -
                            sourceSet.output.files
                    ClasspathGroup.PROJECT -> sourceSet.output.files
                }
                files?.map { ClasspathEntry(jar = it.absolutePath, group = g) }
                    ?: modEntries
            }
            IdeClasspathModel(
                environmentId = environmentId,
                groups = groups,
                excludedDirectories = listOf("run")
            )
        }
    }

    /** Dev-classpath mod view names per mod bucket — `*Direct` for
     *  identity envs (the pick the source set itself makes), the
     *  remapped views otherwise; [sources] swaps in the paired
     *  `-sources.jar` views ([MinecraftExtension.modDevSourcesViewConfigurations]).
     *  Internal for tests. */
    internal fun modViewNames(
        project: Project,
        environment: MinecraftEnvironment,
        sources: Boolean = false
    ): List<String> {
        val environmentId = environment.environmentId
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val suffix = if (minecraft.isIdentityRemap(environmentId).get())
            "Direct" else "Remapped"
        return listOf(
            environment.modImplementationConfigurationName,
            environment.modRuntimeOnlyConfigurationName
        ).map { if (sources) "${it}Sources$suffix" else "$it$suffix" }
    }

    /** MODS entries paired with their dev-namespace `-sources.jar`s —
     *  the sources views resolve each dep's published sources through
     *  the env's SOURCES remap pipeline so IDE navigation lands on
     *  dev-namespace names (loom's per-dep sources-remap parity).
     *  Sources resolution is lenient + component-filtered: deps with
     *  no sources variant contribute nothing, and file deps can't
     *  masquerade as their own sources. Pairing keys on the artifact's
     *  component id, which survives the artifact transforms.
     *  Internal for tests. */
    internal fun modEntries(
        project: Project,
        environment: MinecraftEnvironment,
        group: ClasspathGroup
    ): List<ClasspathEntry> {
        val jarViews = modViewNames(project, environment)
        val sourcesViews = modViewNames(project, environment, sources = true)
        return jarViews.zip(sourcesViews).flatMap { (jarName, sourcesName) ->
            val sourcesByComponent = project.configurations
                .findByName(sourcesName)?.incoming
                ?.artifactView { view ->
                    view.lenient(true)
                    view.componentFilter {
                        it is ModuleComponentIdentifier ||
                                it is ProjectComponentIdentifier
                    }
                }?.artifacts?.artifacts.orEmpty()
                .associate { it.id.componentIdentifier to it.file }
            project.configurations.findByName(jarName)?.incoming
                ?.artifactView { it.lenient(true) }?.artifacts?.artifacts.orEmpty()
                .map { artifact ->
                    ClasspathEntry(
                        jar = artifact.file.absolutePath,
                        sources = sourcesByComponent[
                            artifact.id.componentIdentifier]?.absolutePath,
                        group = group
                    )
                }
        }.distinct()
    }

    /** Files of the env's remapJar task outputs (prod classpath) —
     *  a split client env rides the parent's combined `remapJar`
     *  (its own `<env>Jar` is client-only output and
     *  `splitEnvironmentSourceSets` disables its remap twin). */
    private fun remapJarFiles(
        project: Project, minecraft: MinecraftExtension,
        environmentId: String
    ): Set<java.io.File> {
        val splitClient = environmentId != SourceSet.MAIN_SOURCE_SET_NAME &&
            minecraft.environments.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                ?.splitClientEnvironment?.orNull == environmentId
        val name = if (environmentId == SourceSet.MAIN_SOURCE_SET_NAME ||
            splitClient) "remapJar"
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
