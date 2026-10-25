package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeMinecraftSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec

/**
 * One Minecraft environment, normally bound to the source set named by
 * [environmentId]. Each instance owns independent version, loader, provider, and
 * mapping declarations.
 */
class MinecraftEnvironment internal constructor(
    val environmentId: String,
    private val project: Project,
    objects: ObjectFactory
) : Named {

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
     * The namespace this environment compiles and runs against. When unset,
     * the resolved mapping tree's last namespace is used — e.g. `named`
     * for yarn-style chains, `official` for bare mojmap. Read lazily by
     * downstream wiring via `MinecraftExtension.devNamespace`.
     */
    val devNamespace: Property<String> = objects.property(String::class.java)

    /**
     * Namespaces mod dependencies in this environment arrive in — mods are
     * remapped to [devNamespace]. When unset, the loader plugin's default
     * (`MinecraftExtension.loaderModSourceNamespaces`) applies; fabric-like
     * loaders default to `intermediary`. Auto-wiring supports a single
     * source namespace; for mixed inputs use `remap.remappedViews` directly.
     */
    val modSourceNamespaces: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Access-widener files applied to this environment's dev Minecraft
     * artifact — populated directly or by loader transforms (fabric-family
     * `*.accesswidener` files from dependent mods are merged in by the
     * loader's wiring).
     */
    val accessWideners: ConfigurableFileCollection = objects.fileCollection()

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
    val mappingsSpec = MappingsSpecImpl(objects)

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

    /** Build the provider-facing immutable spec for this environment. */
    fun spec(): MinecraftSpec {
        validateCombinations()
        return RuntimeMinecraftSpec(
            environmentId = environmentId,
            sourceSetName = environmentId,
            version = RuntimeVersionSpec(version.get()),
            side = side.get(),
            loader = loaderSpec.orNull
        )
    }

    internal fun bindSourceSet(sourceSet: SourceSet) {
        boundSourceSet = sourceSet
        combinedWithReferences.forEach(::wireSourceOutput)
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
