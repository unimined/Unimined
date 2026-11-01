package xyz.wagyourtail.unimined.migrate.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.readMaterializedManifest
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceRemapper
import xyz.wagyourtail.unimined.mapping.tree.graft
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.serialization.json.jsonArray
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Migrates a source set's sources onto a different mappings release —
 * the `migrateMappings` of loom/1.x, redesigned (see plan.md §6):
 *
 * - the *target* is a [MappingsSpecImpl] configured via `target {}`
 *   (`yarn("...")`, `mcp(...)`, `maven(...)`, ...), not a second
 *   environment,
 * - the input environment's full resolved tree and the target tree are
 *   **grafted** through an auto-detected shared namespace
 *   ([MappingTree.graft]) into a direct `dev -> target` mapping, then
 *   UML's [SourceRemapper] rewrites `.java`/`.kt` sources in one pass,
 * - non-code files copy verbatim; the result lands in [outputDir]
 *   (loom convention: `remappedSrc`, outside `src/` so it can't become
 *   an accidental source root).
 *
 * Use cases: bumping a named-mappings build (intermediary bridge),
 * dev-ns -> mojmap (`official` bridge), cross-MC-version where
 * intermediary ids line up between releases.
 */
abstract class MigrateMappingsTask @Inject constructor(
    private val objects: ObjectFactory
) : DefaultTask() {

    /** Environment id — labels the resolution context/logging. */
    @get:Input
    abstract val environmentId: Property<String>

    /** The input environment's serialized mapping data (full tree) —
     *  a `produceMappingBundle` output; the file is the
     *  configuration-cache-safe input, data is read back in the
     *  action. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundleFile: RegularFileProperty

    /** The target spec's materialized mapping files — wired from
     *  `materializeSources(targetSpec)` flattened to files +
     *  attachments inside a provider. */
    @get:InputFiles
    @get:Classpath
    abstract val targetMappingFiles: ConfigurableFileCollection

    /** Serialized `MaterializedSourceSpec` manifest for
     *  [targetMappingFiles] — carries each source's nsMap/env tags so
     *  relabels (e.g. gen-2 `clientOfficial` -> `official:client`)
     *  reach the tree; a bare file list would drop them. Same shape
     *  as `ProduceMappingBundleTask.sourcesManifest`. */
    @get:Input
    abstract val targetSourcesManifest: Property<String>

    /** The namespace the input sources are written in (env dev ns). */
    @get:Input
    abstract val devNamespace: Property<String>

    /** The target tree's dev column — convention: the target's first
     *  declared named namespace. */
    @get:Input
    @get:Optional
    abstract val targetDevNamespace: Property<String>

    /** Explicit graft bridge namespace; unset = auto-detect
     *  (env prod ns, then `intermediary`, then `official`). */
    @get:Input
    @get:Optional
    abstract val bridgeNamespace: Property<String>

    /** Bridge candidates in preference order — the env's prod
     *  (mod-source) namespaces, wired by the plugin. */
    @get:Input
    abstract val prodNamespaces: ListProperty<String>

    /** The MC version used constructing the target spec — matters for
     *  helpers like `yarn(build)` that bake the version into coords. */
    @get:Input
    @get:Optional
    abstract val targetMcVersion: Property<String>

    /** The user's target spec — populated by [target] at configuration
     *  time only; `transient` keeps the non-serializable spec object
     *  out of the configuration cache. */
    @get:Internal
    @Transient
    var targetSpec: MappingsSpecImpl? = null
        private set

    /** Source directories to migrate — the source set's `allSource`. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    /** Compile classpath for reference resolution — dev-ns named. */
    @get:Classpath
    abstract val remapClasspath: ConfigurableFileCollection

    /** Global merged-mapping cache root for target-spec resolution. */
    @get:Internal
    abstract val globalCacheDir: org.gradle.api.file.DirectoryProperty

    /** Extra artifacts for the remapper classpath — e.g. the
     *  *new-version* MC jar (`net.fabricmc:intermediary:<ver>`) for
     *  cross-version migrations. Wired from [classpathNotations]. */
    @get:Classpath
    @get:Optional
    abstract val targetClasspath: ConfigurableFileCollection

    /** Maven coordinates added via [classpath] — serializable so the
     *  detached-configuration resolution can run inside a provider. */
    @get:Input
    @get:Optional
    abstract val classpathNotations: ListProperty<String>

    /** Where migrated sources land — convention `remappedSrc`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Add maven coordinates to the remapper classpath — for
     *  cross-version targets, e.g.
     *  `classpath("net.fabricmc:intermediary:1.20.2")`. */
    fun classpath(vararg notations: String) {
        classpathNotations.addAll(*notations)
    }

    /** Configure the target mappings — same DSL as `mappings {}`. */
    fun target(action: MappingsSpecImpl.() -> Unit) {
        val spec = MappingsSpecImpl(objects, targetMcVersion.orElse(""))
        spec.action()
        targetSpec = spec
    }

    @TaskAction
    fun run() {
        val log = logger
        val inputTree = SerializedMappingData.readEither(
            inputBundleFile.get().asFile.toPath()
        ).view<MappingTree>()
            ?: error("input bundle contains no UML MappingTree view")
        val targetTree = readMaterializedManifest(
            targetSourcesManifest.get(),
            environmentId.get(), logger,
            globalCacheDir.orNull?.asFile?.toPath()
        ).view<MappingTree>()
            ?: error("target mapping data does not expose a UML MappingTree view")

        val fromNs = Namespace(devNamespace.get())
        val toNsName = targetDevNamespace.orNull
            ?: listOf("named", "mojmap", "yarn", "mcp")
                .firstOrNull { n -> targetTree.namespaces.any { it.name == n } }
            ?: targetTree.namespaces.last().name
        val toNs = Namespace(toNsName)

        // bridge: explicit > env prod ns > intermediary > official >
        // any common namespace, else fail listing candidates
        val shared = inputTree.namespaces.toSet() intersect targetTree.namespaces.toSet()
        val via = bridgeNamespace.orNull?.let { Namespace(it) }
            ?: (prodNamespaces.get() + listOf("intermediary", "official"))
                .map { Namespace(it) }
                .firstOrNull { it in shared }
            ?: shared.firstOrNull()
            ?: error(
                "cannot migrate: input tree namespaces " +
                        "${inputTree.namespaces} and target tree namespaces " +
                        "${targetTree.namespaces} share none — set " +
                        "`bridgeNamespace` or choose compatible mappings"
            )

        log.lifecycle(
            "[${this.name}] migrating sources {} -> {} via '{}'",
            devNamespace.get(), toNsName, via.name
        )
        val tree = inputTree.graft(targetTree, via, fromNs, toNs)

        // pack allSource into a scratch jar — SourceRemapper's input is
        // an archive; non-code entries ride along untouched
        val scratch = temporaryDir.toPath().resolve("migrate-input.jar")
        // allSource may mix dirs and files, and dirs may overlap —
        // skip empty/absent relative paths and dedupe entries
        val seen = mutableSetOf<String>()
        ZipOutputStream(scratch.outputStream()).use { z ->
            sourceDirs.files.forEach { root ->
                val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }
                    else sequenceOf(root)
                files.forEach { f ->
                    val rel = if (root.isDirectory)
                        f.relativeTo(root).invariantSeparatorsPath else f.name
                    if (rel.isNotEmpty() && seen.add(rel)) {
                        z.putNextEntry(ZipEntry(rel))
                        z.write(f.readBytes())
                        z.closeEntry()
                    }
                }
            }
        }

        val result = SourceRemapper.remap(
            scratch, tree, fromNs, toNs,
            classpath = (remapClasspath.files + targetClasspath.files)
                .map { it.toPath() }
                .filter { java.nio.file.Files.exists(it) }
        )

        val out = outputDir.get().asFile.toPath()
        for ((path, bytes) in result.sources) {
            out.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
        }
        for ((path, bytes) in result.resources) {
            out.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
        }
        for ((file, line, msg) in result.errors) {
            log.warn("[${this.name}] $file:$line: $msg")
        }
        log.lifecycle("[${this.name}] migrated ${result.sources.size} sources -> $out")
    }

}
