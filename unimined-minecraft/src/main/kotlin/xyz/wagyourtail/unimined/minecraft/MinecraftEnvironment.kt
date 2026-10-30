package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeMinecraftSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import javax.inject.Inject

/**
 * One Minecraft environment, normally bound to the source set named by
 * [environmentId]. Each instance owns independent version, loader, provider, and
 * mapping declarations.
 */
class MinecraftEnvironment internal constructor(
    val environmentId: String,
    private val project: Project,
    objects: ObjectFactory
) : Named, ExtensionAware {

    // extension container from a decorated managed object — the public
    // API way to back ExtensionAware on a non-managed domain object,
    // required so plugin DSL objects registered on the env dispatch
    // from Groovy.
    private val extensionContainer =
        objects.newInstance(MinecraftEnvExtensionHolder::class.java).extensions

    override fun getExtensions(): ExtensionContainer = extensionContainer

    override fun getName(): String = environmentId

    /** Minecraft version id for this source set. */
    val version: Property<String> = objects.property(String::class.java)

    /** Client/server/merged side for this source set; defaults to merged. */
    val side: Property<Side> = objects.property(Side::class.java).convention(Side.MERGED)

    /** Optional provider override for this source set. */
    val provider: Property<String> = objects.property(String::class.java)

    /** Declared modloader for this environment — read by transform plugins
     *  to contribute loader-specific wiring (dependency defaults, namespace
     *  conventions, transformers). */
    val loaderSpec: Property<LoaderSpec> = objects.property(LoaderSpec::class.java)

    /**
     * Base filename for the produced Minecraft artifacts — downstream
     * transforms append `-<key>`/`-<namespace>` suffixes onto it.
     * Loaders override the `minecraft-<env>-<version>` default when a
     * runtime probes the classpath by filename (FML's
     * `FileUtils.matchFileName` searches `contains(<artifact>-<version>)`,
     * so forge-family environments name theirs `forge-<fullVersion>`).
     */
    val artifactBaseName: Property<String> =
        objects.property(String::class.java).convention(
            version.map { "minecraft-$environmentId-$it" })

    /**
     * The namespace this environment compiles and runs against. When unset,
     * the resolved mapping tree's last namespace is used — e.g. `named`
     * for yarn-style chains, `official` for bare mojmap. Read lazily by
     * downstream wiring via `MinecraftExtension.devNamespace`.
     */
    val devNamespace: Property<String> = objects.property(String::class.java)

    /**
     * The namespace jar content arrives at the dev pipeline's core
     * remap step in — normally the source namespace, but loader steps
     * may relocate content mid-pipeline (forge userdev's binpatch
     * substitutes the srg-named clean jar). When unset,
     * `MinecraftExtension.sourceNamespace` applies.
     */
    val devRemapSourceNamespace: Property<String> = objects.property(String::class.java)

    /**
     * Namespaces mod dependencies in this environment arrive in — mods are
     * remapped to [devNamespace]. When unset, the loader plugin's default
     * (`MinecraftExtension.loaderModSourceNamespaces`) applies; fabric-like
     * loaders default to `intermediary`. Auto-wiring supports a single
     * source namespace; for mixed inputs use `remap.remappedViews` directly.
     */
    val modSourceNamespaces: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Whether unimined auto-wires `remap<JarTask>` production tasks for
     * this environment's source-set jar tasks (the loom/1.x convention:
     * the input jar gains a `-dev` classifier, the remap task produces
     * the prod-namespace artifact under the original classifier).
     */
    val defaultRemapJar: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Access-widener files applied to this environment's dev Minecraft
     * artifact — populated directly or by loader transforms (fabric-family
     * `*.accesswidener` files from dependent mods are merged in by the
     * loader's wiring).
     */
    val accessWideners: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Whether access wideners declared by compile-classpath mod
     * dependencies contribute their `transitive-*` directives to this
     * environment's dev Minecraft artifact (Loom's
     * `transitiveAccessWideners` toggle; default `true`). Disable to
     * isolate the dev artifact from dependency-declared widening.
     */
    val transitiveAccessWideners: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Access-transformer files applied to this environment's dev
     * Minecraft artifact (Forge/FML-family `accesstransformer.cfg` /
     * `*_at.cfg` files) — populated directly or by loader transforms.
     * ATs apply unconditionally, including ones extracted from mod
     * dependencies — FML has no transitive-gated subset.
     */
    val accessTransformers: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Predicates consulted when the env's provider-declared MC
     * libraries populate `minecraftLibraries`/`minecraftDeclaredLibraries`
     * — a dep is kept only when every filter accepts it. Transform
     * wiring contributes exclusions (e.g. FG1-era FML dropping
     * `org.ow2.asm` because it supplies its own `asm-all`).
     */
    val libraryFilters: MutableList<(org.gradle.api.artifacts.Dependency) -> Boolean> =
        mutableListOf()

    /** Contribute a library keep-filter — see [libraryFilters]. */
    fun libraryFilter(filter: (org.gradle.api.artifacts.Dependency) -> Boolean) {
        libraryFilters += filter
    }

    /** Dependency bucket for the generated Minecraft artifact stub. */
    val minecraftConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraft"
        } else {
            "${environmentId}Minecraft"
        }

    /** Dependency bucket for the environment's resolved MC library deps. */
    val minecraftLibrariesConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraftLibraries"
        } else {
            "${environmentId}MinecraftLibraries"
        }

    /** Resolvable view producing the raw (source-namespace) MC artifact. */
    val minecraftRawViewName: String
        get() = if (environmentId == "main") {
            "minecraftRaw"
        } else {
            "${environmentId}MinecraftRaw"
        }

    /** Resolvable view producing the dev-namespace MC artifact. */
    val minecraftDevViewName: String
        get() = if (environmentId == "main") {
            "minecraftDev"
        } else {
            "${environmentId}MinecraftDev"
        }

    /** The provider-declared library set only (version-json deps — no
     *  user/transform contributions); feeds the merge strip's
     *  bundled-library membership. */
    val minecraftDeclaredLibrariesConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraftDeclaredLibraries"
        } else {
            "${environmentId}MinecraftDeclaredLibraries"
        }

    /** Resolvable view producing the mod-source-namespace MC artifact —
     *  the mod-view remap-analysis classpath entry. */
    val minecraftModSourceViewName: String
        get() = if (environmentId == "main") {
            "minecraftModSource"
        } else {
            "${environmentId}MinecraftModSource"
        }

    /** Dependency bucket for mods used to compile this environment. */
    val modImplementationConfigurationName: String
        get() = if (environmentId == "main") {
            "modImplementation"
        } else {
            "${environmentId}ModImplementation"
        }

    /** Dependency bucket for mods used only at runtime in this environment. */
    val modRuntimeOnlyConfigurationName: String
        get() = if (environmentId == "main") {
            "modRuntimeOnly"
        } else {
            "${environmentId}ModRuntimeOnly"
        }

    /**
     * Classes and resources from environments passed to [combineWith]. The
     * future mod packaging plugin consumes this collection; it is exposed so
     * integrations do not need to rediscover compose requests.
     */
    val combinedOutputs: ConfigurableFileCollection = objects.fileCollection()

    /** Source sets from [combineWith], for generated source jars. */
    val combinedSources: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Transform-contributed dev-sources override — when present
     * (e.g. a forge-userdev mcpconfig chain that produces sources
     * directly), `genSources` consumes this jar instead of running the
     * decompiler, remapping it from [devSourcesNamespace] to the dev
     * namespace. Absent by default; transforms set it lazily.
     */
    val devSources: RegularFileProperty = objects.fileProperty()

    /** Namespace of [devSources]' contents (e.g. `searge`/`mojmap`). */
    val devSourcesNamespace: Property<String> = objects.property(String::class.java)

    /** Environments whose source-set output is composed into this one. */
    val combinedWith: Set<EnvironmentReference>
        get() = combinedWithReferences

    private val combinedWithReferences = linkedSetOf<EnvironmentReference>()
    private val wiredOutputReferences = mutableSetOf<EnvironmentReference>()
    private var boundSourceSet: SourceSet? = null

    /**
     * This environment's declared mapping sources — the object
     * [mappings] configures. Exposed so transform plugins can contribute
     * `defaultSources` (e.g. fabric's intermediary hop).
     */
    val mappingsSpec = MappingsSpecImpl(objects, version)

    /** Configure this environment's modloader and version. */
    fun loader(name: String, version: String) {
        loaderSpec.set(RuntimeLoaderSpec(name, version))
    }

    /** Configure this environment's mapping sources. */
    fun mappings(action: Action<MappingsSpecImpl>) {
        action.execute(mappingsSpec)
    }

    /** Groovy DSL overload for this environment's mapping sources. */
    fun mappings(
        @DelegatesTo(value = MappingsSpecImpl::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegate = mappingsSpec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /**
     * Reuse all default configuration sections from [path] without composing
     * its compiled output. Use `sourceSetName` for this project or
     * `projectPath:sourceSetName` for another project.
     */
    fun copyFrom(path: String) {
        copySections(parseEnvironmentPath(path), EnvironmentSection.COPY_DEFAULT, false)
    }

    /** Reuse selected configuration sections from [path]. */
    fun copyFrom(path: String, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "copyFrom requires at least one section" }
        copySections(parseEnvironmentPath(path), sections.toSet(), true)
    }

    /** Reuse all default sections from [sourceSet] in this project. */
    fun copyFrom(sourceSet: SourceSet) {
        copyFrom(project, sourceSet)
    }

    /** Reuse selected sections from [sourceSet] in this project. */
    fun copyFrom(sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        copyFrom(project, sourceSet, *sections)
    }

    /** Reuse all default sections from another project's [sourceSet]. */
    fun copyFrom(project: Project, sourceSet: SourceSet) {
        copySections(
            EnvironmentReference(project.path, sourceSet.name),
            EnvironmentSection.COPY_DEFAULT,
            false
        )
    }

    /** Reuse selected sections from another project's [sourceSet]. */
    fun copyFrom(project: Project, sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "copyFrom requires at least one section" }
        copySections(
            EnvironmentReference(project.path, sourceSet.name),
            sections.toSet(),
            true
        )
    }

    /** Compatibility alias for [copyFrom] using all default sections. */
    fun from(path: String) = copyFrom(path)

    /** Compatibility alias for [copyFrom] using all default sections. */
    fun from(sourceSet: SourceSet) = copyFrom(sourceSet)

    /** Compatibility alias for cross-project [copyFrom]. */
    fun from(project: Project, sourceSet: SourceSet) = copyFrom(project, sourceSet)

    /**
     * Compose [path]'s source-set output into this environment's compile and
     * runtime classpaths, and retain it as a packaged mod input.
     *
     * By default this copies common environment sections but not the source
     * environment's loader. Request [EnvironmentSection.LOADER] explicitly to
     * inherit that section too.
     */
    fun combineWith(path: String) {
        combineWith(parseEnvironmentPath(path), EnvironmentSection.COMBINE_DEFAULT, false)
    }

    /** Compose output and copy selected sections from [path]. */
    fun combineWith(path: String, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "combineWith requires at least one section" }
        combineWith(parseEnvironmentPath(path), sections.toSet(), true)
    }

    /** Compose output from [sourceSet] in this project. */
    fun combineWith(sourceSet: SourceSet) {
        combineWith(project, sourceSet)
    }

    /** Compose output and copy selected sections from [sourceSet]. */
    fun combineWith(sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        combineWith(project, sourceSet, *sections)
    }

    /** Compose output from another project's [sourceSet]. */
    fun combineWith(project: Project, sourceSet: SourceSet) {
        combineWith(
            EnvironmentReference(project.path, sourceSet.name),
            EnvironmentSection.COMBINE_DEFAULT,
            false
        )
    }

    /** Compose output and copy selected sections from another project's [sourceSet]. */
    fun combineWith(project: Project, sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "combineWith requires at least one section" }
        combineWith(
            EnvironmentReference(project.path, sourceSet.name),
            sections.toSet(),
            true
        )
    }

    /** Merge keep-override entry globs — server-jar entries matching
     *  are never stripped as bundled-library copies (the 1.x
     *  `includeGlobs` escape hatch, minus its whitelist semantics —
     *  stripping is membership-based now). */
    val mergeIncludeGlobs: ListProperty<String> = objects
        .listProperty(String::class.java).convention(emptyList())

    /** Extra entry globs stripped from the *server* jar during merge —
     *  for bundled content absent from `minecraftLibraries`
     *  (undeclared embedded deps). */
    val mergeExcludeGlobs: ListProperty<String> = objects
        .listProperty(String::class.java).convention(emptyList())

    /** Master switch for the server-bundled-library strip — pre-1.13
     *  server jars embed their runtime libraries inline; entries
     *  whose paths also exist in a `minecraftLibraries` jar are
     *  dropped from the merged artifact. */
    val stripBundledServerLibraries: Property<Boolean> = objects
        .property(Boolean::class.java).convention(true)

    /** Build the provider-facing immutable spec for this environment. */
    fun spec(): MinecraftSpec {
        validateCombinations()
        return RuntimeMinecraftSpec(
            environmentId = environmentId,
            sourceSetName = environmentId,
            version = RuntimeVersionSpec(version.get()),
            side = side.get(),
            loader = loaderSpec.orNull,
            mergeIncludeGlobs = mergeIncludeGlobs.getOrElse(emptyList()),
            mergeExcludeGlobs = mergeExcludeGlobs.getOrElse(emptyList()),
            stripBundledServerLibraries = stripBundledServerLibraries.get()
        )
    }

    /**
     * Whether tool executions (mcpconfig steps, AT tools, etc.),
     * source-set compiles, and run launchers resolve Gradle java
     * toolchains. Conventions to
     * [MinecraftExtension.useToolchains] (the global kill-switch);
     * 1.x `useToolchains` semantics — opt-out: set false to run on
     * the build JVM (errors when the env requires a newer Java).
     */
    val useToolchains: Property<Boolean> =
        objects.property(Boolean::class.java)
            .convention(project.providers.provider {
                // env instances are constructed while MinecraftExtension
                // itself is being created — resolve lazily
                project.extensions.getByType(MinecraftExtension::class.java)
                    .useToolchains.get()
            })

    /**
     * Java floor for this environment — the transform wiring
     * conventions it from the loader descriptor's `javaVersion`
     * (cleanroom→21, ornithe→17); users may set it directly to force
     * a higher requirement (`minecraft { javaVersion = 21 }`).
     */
    val javaVersion: Property<Int> = objects.property(Int::class.java)

    /**
     * Effective java requirement — the version-json `javaVersion`
     * (provider capabilities) raised by [javaVersion]. Feeds the
     * compile toolchain / `release`, run launchers, and tool-exec
     * fallbacks.
     */
    val requiredJavaVersion: Provider<Int> = project.provider {
        maxOf(
            project.extensions.getByType(MinecraftExtension::class.java)
                .artifacts(environmentId).get().capabilities.get().javaVersion,
            javaVersion.getOrElse(0)
        )
    }

    internal fun bindSourceSet(sourceSet: SourceSet) {
        boundSourceSet = sourceSet
        combinedWithReferences.forEach(::wireSourceOutput)
        wireJavaCompatibility(sourceSet)
    }

    /**
     * Sets the environment's java requirement ([requiredJavaVersion])
     * on its compile tasks: toolchain compiler + `--release` when
     * [useToolchains] is on (release only for required >= 9 — javac 8
     * rejects the flag); source/target compatibility always (in
     * `doFirst`, where the lazy requirement resolves) + the build-JVM
     * floor check when toolchains are off (1.x opt-out semantics).
     */
    private fun wireJavaCompatibility(sourceSet: SourceSet) {
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        project.tasks.matching { it.name == sourceSet.getCompileTaskName("java") }
            .configureEach { task ->
            val compile = task as? JavaCompile ?: return@configureEach
            compile.options.release.set(
                requiredJavaVersion.map { if (it <= 8) null else it })
            compile.javaCompiler.set(project.providers.provider {
                if (!useToolchains.get()) null
                else toolchains.compilerFor {
                    it.languageVersion.set(requiredJavaVersion.map(JavaLanguageVersion::of))
                }.orNull
            })
            // locals only — a `this` capture serializes the whole
            // environment graph (config cache)
            val required = requiredJavaVersion
            val toolchainsOn = useToolchains
            val envId = environmentId
            compile.doFirst("unimined java compat") {
                val req = required.get()
                compile.sourceCompatibility = req.toString()
                compile.targetCompatibility = req.toString()
                checkJavaFloor(toolchainsOn, envId, req)
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            KotlinWiring.wire(
                project, sourceSet, environmentId,
                requiredJavaVersion, useToolchains
            )
        }
    }

    private companion object {

        /** The build-JVM floor check for the toolchains-off path. */
        private fun checkJavaFloor(
            useToolchains: Provider<Boolean>,
            environmentId: String,
            required: Int
        ) {
            if (!useToolchains.get() &&
                JavaVersion.current() < JavaVersion.toVersion(required)) {
                throw GradleException(
                    "unimined: environment '$environmentId' requires " +
                        "Java $required but toolchains are disabled " +
                        "and the build JVM is ${JavaVersion.current()}")
            }
        }
    }

    /**
     * `jvmTarget` wiring for `compile<Env>Kotlin` — everything KGP
     * lives here so java-only builds never link the kotlin-gradle
     * classes (nested objects load on first reference, which only
     * happens inside the `withPlugin` guard).
     */
    private object KotlinWiring {

        fun wire(
            project: Project,
            sourceSet: SourceSet,
            environmentId: String,
            requiredJavaVersion: Provider<Int>,
            useToolchains: Provider<Boolean>
        ) {
            val toolchains = project.extensions
                .getByType(JavaToolchainService::class.java)
            project.tasks.matching {
                it.name == sourceSet.getCompileTaskName("kotlin")
            }.configureEach { task ->
                try {
                    // kotlinc's JDK — same launcher resolution as the
                    // java toolchain; empty provider (toolchains off)
                    // leaves KGP on the build JVM
                    if (task is org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain) {
                        task.kotlinJavaToolchain.toolchain.use(
                            useToolchains.flatMap { use ->
                                if (!use) project.provider { null }
                                else toolchains.launcherFor {
                                    it.languageVersion.set(
                                        requiredJavaVersion.map(JavaLanguageVersion::of))
                                }
                            }
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val compile = task as? org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<
                        org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions>
                    if (compile == null) {
                        task.logger.warn(
                            "unimined: '${task.name}' is not a KotlinCompilationTask — jvmTarget not set")
                    } else {
                        compile.compilerOptions.jvmTarget.set(
                            requiredJavaVersion.map(::jvmTarget))
                    }
                } catch (e: NoClassDefFoundError) {
                    task.logger.warn(
                        "unimined: could not wire '${task.name}' — " +
                            "Kotlin Gradle plugin is too old for the compilerOptions API", e)
                }
                task.doFirst("unimined kotlin java floor") {
                    val required = requiredJavaVersion.get()
                    if (!useToolchains.get() &&
                        JavaVersion.current() < JavaVersion.toVersion(required)) {
                        throw GradleException(
                            "unimined: environment '$environmentId' requires " +
                                "Java $required but toolchains are disabled " +
                                "and the build JVM is ${JavaVersion.current()}")
                    }
                }
            }
        }

        private fun jvmTarget(
            required: Int
        ): org.jetbrains.kotlin.gradle.dsl.JvmTarget = when {
            required <= 8 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
            else -> try {
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.valueOf("JVM_$required")
            } catch (_: IllegalArgumentException) {
                // newer than this KGP can express — clamp to its max
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.values().last()
            }
        }

    }

    internal fun validateCombinations() {
        project.extensions.getByType(MinecraftExtension::class.java)
            .validateCombinations(this)
    }

    private fun copySections(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) {
        checkSelfReference(source)
        EnvironmentCopyRegistry.record(
            project,
            EnvironmentCopyRequest(
                source = source,
                target = EnvironmentReference(project.path, environmentId),
                sections = sections
            ),
            requireHandlers
        )
    }

    private fun combineWith(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) {
        checkSelfReference(source)
        copySections(source, sections, requireHandlers)
        if (combinedWithReferences.add(source)) {
            boundSourceSet?.let { wireSourceOutput(source, it) }
        }
    }

    private fun checkSelfReference(source: EnvironmentReference) {
        if (source.projectPath == project.path && source.environmentId == environmentId) {
            throw GradleException(
                "Minecraft environment '$environmentId' cannot copy or combine with itself"
            )
        }
    }

    private fun wireSourceOutput(source: EnvironmentReference, target: SourceSet = boundSourceSet!!) {
        if (!wiredOutputReferences.add(source)) return
        val output = project.files(project.provider { sourceSet(source).output })
        // Gradle 9 marks resolvable compile/runtime classpaths non-declarable.
        // Adding the output to compileOnly+runtimeOnly places it on both
        // classpaths without mutating the resolvable configurations directly.
        project.dependencies.add(target.compileOnlyConfigurationName, output)
        project.dependencies.add(target.runtimeOnlyConfigurationName, output)
        combinedOutputs.from(output)
        combinedSources.from(project.provider { sourceSet(source).allSource })
    }

    private fun sourceSet(source: EnvironmentReference): SourceSet {
        val sourceProject = if (source.projectPath == project.path) {
            project
        } else {
            project.project(source.projectPath)
        }
        val sourceSets = sourceProject.extensions.findByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ) ?: throw GradleException(
            "Cannot combine '${project.path}:$environmentId' with " +
                    "'${source.projectPath}:${source.environmentId}': the source " +
                    "project has no Java source sets"
        )
        return sourceSets.getByName(source.environmentId)
    }

    private fun parseEnvironmentPath(path: String): EnvironmentReference {
        if (!path.contains(":")) {
            return EnvironmentReference(project.path, path)
        }
        val projectPath = path.substringBeforeLast(":").ifEmpty { ":" }
        val environmentId = path.substringAfterLast(":")
        require(environmentId.isNotEmpty()) {
            "Environment path '$path' must end with a source-set name"
        }
        return EnvironmentReference(projectPath, environmentId)
    }

}

/** Managed-object shell whose decorated extension container backs
 *  [MinecraftEnvironment.getExtensions] — Gradle provides the real
 *  container; nothing is ever registered on this type itself. */
internal abstract class MinecraftEnvExtensionHolder @Inject constructor() : ExtensionAware
