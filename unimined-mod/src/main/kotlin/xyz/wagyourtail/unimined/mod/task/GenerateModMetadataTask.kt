package xyz.wagyourtail.unimined.mod.task

import org.gradle.work.DisableCachingByDefault
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
import xyz.wagyourtail.unimined.mod.MixinConfigData
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.ModMetadataEmitterRegistry
import xyz.wagyourtail.unimined.mod.internal.MetadataJsonMerge
import xyz.wagyourtail.unimined.mod.internal.ModMetadataScan
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile
import xyz.wagyourtail.unimined.remapper.access.AccessFormatConvert
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

/**
 * Emits generated mod metadata + mixin configs for one bound environment
 * into [outputDir], which the packaging wiring registers as a resources
 * srcDir. Everything materializes at execution: emitters rediscover via
 * ServiceLoader so no loader objects sit in serialized task state.
 *
 * Emitted paths that already exist in [resourceDirs] are MERGED, not
 * rejected: JSON documents deep-merge (objects recurse, arrays union,
 * generated scalars win) and the manual source files are listed in
 * [mergedPathsFile] so the packaging wiring excludes them from
 * `processResources` — the merged copy is the only one packaged.
 * Non-JSON collisions still fail.
 *
 * The task also auto-fills what the `mod {}` model can't express:
 * hand-written `*.mixins.json` files in resources are discovered and
 * referenced from loader metadata, classes implementing the emitter's
 * [ModMetadataEmitter.gametestInterface] become gametest entrypoints,
 * and interfaces implemented by mixin classes are emitted as a
 * `transitive-inject-interface` classTweaker (declared via the loader's
 * `classTweakers` metadata key and `custom.*injected_interfaces`).
 */
@DisableCachingByDefault
abstract class GenerateModMetadataTask : DefaultTask() {

    /** Loader-neutral model snapshot (serializable). */
    @get:Input
    abstract val metadata: Property<ModMetadata>

    /** Generated mixin configs (serializable). */
    @get:Input
    abstract val mixinConfigs: ListProperty<MixinConfigData>

    /** Environment loader name selecting the emitter (`fabric`, ...). */
    @get:Input
    @get:Optional
    abstract val loaderName: Property<String>

    @get:Input
    abstract val environmentId: Property<String>

    /** Aux envs (datagen/gametest children) emit nothing — their
     *  classes are referenced by the parent env's metadata, and a
     *  second same-id metadata file would collide on the classpath.
     *  Read at execution: env binding fires before `datagen()`/
     *  `gametest()` mark the env. Named without the `is` prefix —
     *  Gradle only accepts `is`-getters for `boolean`/`Boolean`
     *  returns, not `Property<Boolean>`. */
    @get:Input
    abstract val auxEnv: Property<Boolean>

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    abstract val side: Property<String>

    @get:Input
    abstract val devNamespace: Property<String>

    @get:Input
    abstract val prodNamespaces: ListProperty<String>

    /** Existing resource roots — scanned for emitted-path merges and
     *  discovered mixin/access files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirs: ConfigurableFileCollection

    /** Compiled class dirs of this env's source set plus its declared
     *  gametest envs — scanned for gametest entrypoints and mixin
     *  interface implementations. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    /** Project-declared access files (AW/CT/AT) — scanned for existing
     *  `inject-interface` rules so generation never duplicates them,
     *  and converted into the loader's consumed format when
     *  [accessFormat]/[accessPath] are set. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredAccessFiles: ConfigurableFileCollection

    /** Serialized mapping bundle carrying the env's dev-namespace
     *  tree — only attached when the loader consumes AW and AT-family
     *  inputs are declared (AT→AW member-descriptor resolution needs
     *  the tree; AW→AT needs nothing). */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val accessBundle: ConfigurableFileCollection

    /** Resolved loader version — emitters fold it into manifest
     *  attributes (`Fabric-Loader-Version`). Unset on loader-less
     *  envs. */
    @get:Input
    @get:Optional
    abstract val loaderVersion: Property<String>

    /** Loader-contributed manifest attributes from the env's
     *  `manifestAttributes` channel — emitters merge them into their
     *  [ModMetadataEmitter.MANIFEST_PATH] body. */
    @get:Input
    abstract val manifestAttributes: MapProperty<String, String>

    /** Per-file access-declaration overrides (`accessFile(...)` on the
     *  env) — custom output/declare/format controls outside the
     *  collection→canonical merge. */
    @get:Input
    abstract val customAccessFiles: ListProperty<DeclaredAccessFile>

    /** Generated output; wired into the source set's resources. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Manifest merge source — the packaging wiring folds it into the
     *  environment jar's manifest via `manifest.from(...)`. Emitters
     *  write their manifest body under [ModMetadataEmitter.MANIFEST_PATH];
     *  a minimal `Manifest-Version` manifest lands here otherwise so the
     *  merge is always a no-op-safe input. */
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    /** Absolute paths of manual resource files merged into generated
     *  output — one per line; the packaging wiring excludes them from
     *  `processResources` so generated+manual content ships exactly once. */
    @get:OutputFile
    abstract val mergedPathsFile: RegularFileProperty

    @TaskAction
    fun generate() {
        if (auxEnv.get()) {
            // still produce the file outputs — the jar manifest merge
            // and processResources exclusion read them unconditionally
            Files.createDirectories(outputDir.get().asFile.toPath())
            Files.createDirectories(manifestFile.get().asFile.toPath().parent)
            Files.write(
                manifestFile.get().asFile.toPath(),
                "Manifest-Version: 1.0\r\n\r\n".toByteArray()
            )
            Files.createDirectories(mergedPathsFile.get().asFile.toPath().parent)
            Files.write(mergedPathsFile.get().asFile.toPath(), ByteArray(0))
            return
        }
        val meta = metadata.get()
        val mixins = mixinConfigs.get()
        val loader = loaderName.orNull

        if (loader == null && meta.sections.isNotEmpty()) {
            throw GradleException(
                "mod { loader(...) } declared on environment " +
                        "'${environmentId.get()}' but the environment declares " +
                        "no loader — the emitted metadata would never attach"
            )
        }
        validate(meta)

        val emitted = linkedMapOf<String, ByteArray>()
        for (mixin in mixins) emitted[mixin.fileName] = mixinJson(mixin)

        // merged/excluded source paths — the access conversion below
        // records into this, so it must precede emitter selection
        val mergedSources = LinkedHashSet<String>()

        // env-declared access files converted to the loader's consumed
        // format at its canonical jar path — resource shaping, so it
        // runs identically on identity-mapped envs (unobfuscated 26.x)
        // where the remap pipeline never fires; remap-access still
        // owns namespace translation on remapping envs
        var accessFile: String? = null
        var accessOutputs = emptyList<AccessOutput>()
        if (loader != null) {
            val emitter = ModMetadataEmitterRegistry().select(loader)
            accessOutputs = convertDeclaredAccess(
                emitter, meta.id, mergedSources)
            // metadata declares the first emitted file — the canonical
            // output when the default merge produced one
            accessFile = accessOutputs.firstOrNull { it.declare }?.path
        }

        // emitters require `id` — an unconfigured `mod {}` in a loader
        // environment emits nothing so a manual metadata file stays
        // authoritative
        if (loader != null && meta.id != null) {
            val emitter = ModMetadataEmitterRegistry().select(loader)

            // hand-written mixin configs ship as resources but nothing
            // references them — surface them to the emitter
            val generatedNames = mixins.mapTo(HashSet()) { it.fileName }
            val discovered = ModMetadataScan.discoverMixinConfigs(
                resourceDirs.files, exclude = generatedNames
            )
            val mixinClassNames = mixins.flatMap { cfg ->
                (cfg.mixins + cfg.client + cfg.server)
                    .map { "${cfg.packageName}.$it" }
            } + discovered.flatMap { it.second.classes }

            val classDirs = classesDirs.files
            var interfaceInjections = emptyMap<String, List<String>>()
            var classTweakerRefs = emptyList<String>()
            var gametestEntrypoints = emptyList<String>()
            var clientGametestEntrypoints = emptyList<String>()

            if (mixinClassNames.isNotEmpty() && classDirs.isNotEmpty()) {
                val existing = ModMetadataScan.existingInterfaceRules(
                    declaredAccessFiles.files + discoverAccessFiles()
                )
                interfaceInjections = ModMetadataScan.interfaceInjections(
                    mixinClassNames, classDirs, existing
                )
                if (interfaceInjections.isNotEmpty()) {
                    val ctPath = GENERATED_CT_PATH
                    emitted[ctPath] = classTweakerText(
                        interfaceInjections, devNamespace.get()
                    )
                    classTweakerRefs = listOf(ctPath)
                }
            }
            emitter.gametestInterface?.let { iface ->
                gametestEntrypoints = ModMetadataScan.gametestClasses(
                    classDirs, iface.replace('.', '/'),
                    emitter.gametestAnnotation?.replace('.', '/')
                )
            }
            emitter.clientGametestInterface?.let { iface ->
                clientGametestEntrypoints = ModMetadataScan.gametestClasses(
                    classDirs, iface.replace('.', '/')
                )
            }

            emitted += emitter.emit(
                ModMetadataEmitter.EmitContext(
                    metadata = meta,
                    mixinFiles = mixins.map { it.fileName },
                    discoveredMixinConfigs = discovered.map { it.first },
                    gametestEntrypoints = gametestEntrypoints,
                    clientGametestEntrypoints = clientGametestEntrypoints,
                    interfaceInjections = interfaceInjections,
                    classTweakerRefs = classTweakerRefs,
                    accessFile = accessFile,
                    environmentId = environmentId.get(),
                    minecraftVersion = minecraftVersion.get(),
                    loaderVersion = loaderVersion.orNull,
                    manifestAttributes = manifestAttributes.get(),
                    side = side.get(),
                    devNamespace = devNamespace.get(),
                    prodNamespaces = prodNamespaces.get()
                )
            )
        }

        // manifest-attr loaders (foxloader) emit their manifest body
        // under the reserved key — it rides manifestFile to the jar
        // task's manifest merge, never the resource output
        val manifest = emitted.remove(ModMetadataEmitter.MANIFEST_PATH)
        Files.createDirectories(
            manifestFile.get().asFile.toPath().parent
        )
        Files.write(
            manifestFile.get().asFile.toPath(),
            manifest ?: "Manifest-Version: 1.0\r\n\r\n".toByteArray()
        )

        // merge emitted files into matching manual resources; record
        // every merged source path so processResources drops them
        val out = outputDir.get().asFile.toPath()
        for ((path, content) in emitted) {
            val matches = resourceDirs.files.mapNotNull { dir ->
                val f = dir.toPath().resolve(path)
                if (Files.isRegularFile(f)) f else null
            }
            val bytes = if (matches.isEmpty()) {
                content
            } else if (path.endsWith(".json", ignoreCase = true)) {
                // manual files merge in srcDir order (later dirs win
                // scalars among themselves), then the generated doc
                // overlays — the `mod {}` DSL stays authoritative
                var manual: ByteArray? = null
                for (f in matches) {
                    val b = Files.readAllBytes(f)
                    manual = if (manual == null) b
                    else MetadataJsonMerge.merge(manual!!, b)
                }
                matches.forEach { f -> mergedSources += f.toAbsolutePath().toString() }
                MetadataJsonMerge.merge(manual!!, content)
            } else {
                throw GradleException(
                    "generated mod metadata '$path' collides with the " +
                            "manual file ${matches.first()} — only JSON " +
                            "metadata merges; remove the manual file or " +
                            "drop the generated declaration"
                )
            }
            val target = out.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        // converted access files — written outside the emitted-merge
        // loop: the manual sources they may supersede are already
        // recorded in mergedSources, and access files aren't
        // JSON-mergeable
        for (o in accessOutputs) {
            val target = out.resolve(o.path)
            Files.createDirectories(target.parent)
            Files.write(target, o.bytes)
        }

        Files.createDirectories(
            mergedPathsFile.get().asFile.toPath().parent
        )
        Files.write(
            mergedPathsFile.get().asFile.toPath(),
            mergedSources.joinToString("\n").toByteArray()
        )
    }

    /**
     * Env-declared access files → `(canonicalPath, bytes)` in the
     * loader's consumed format, or `null` when the loader has no
     * access-file convention or nothing is declared. A manual file
     * already at the canonical path folds in as another input; every
     * consumed source lands in [mergedSources] so the original doesn't
     * ALSO ship from its resource location. Inputs in the loader's own
     * format still re-emit canonically — env-declared files may live
     * outside the resource roots and never reach the jar otherwise.
     */
    /** One converted access file — `declare=false` suppresses the
     *  loader-metadata reference (custom `accessFile(declare=false)`). */
    private data class AccessOutput(
        val path: String,
        val bytes: ByteArray,
        val declare: Boolean
    )

    private fun convertDeclaredAccess(
        emitter: ModMetadataEmitter,
        modId: String?,
        mergedSources: MutableSet<String>
    ): List<AccessOutput> {
        val format = emitter.accessFormat
            ?.let(AccessFormatConvert.Format::byName)
        val pathTemplate = emitter.accessPath
        if (format == null || pathTemplate.isNullOrEmpty()) {
            return emptyList()
        }

        val overrides = customAccessFiles.get()
        val canonical = pathTemplate.replace(
            "{modid}", modId ?: "mod")
        // forced source formats must be registered before any
        // conversion runs (canonical merge included)
        specSourceFormats.clear()
        overrides.forEach { spec ->
            spec.format?.let { name ->
                specSourceFormats[Paths.get(spec.path)
                    .toAbsolutePath().normalize()] =
                    AccessFormatConvert.Format.byName(name)
                        ?: throw GradleException(
                            "accessFile(format='$name') isn't a known " +
                                    "access format (aw, at, legacy_at)")
            }
        }
        val overridePaths = overrides.mapTo(HashSet()) {
            Paths.get(it.path).toAbsolutePath().normalize()
        }
        val inputs = declaredAccessFiles.files.map { it.toPath() }
            .filter(Files::isRegularFile)
            .filter {
                it.toAbsolutePath().normalize() !in overridePaths
            }
        val manual = resourceDirs.files.mapNotNull { dir ->
            dir.toPath().resolve(canonical).takeIf(Files::isRegularFile)
        }
        // overrides without an explicit output join the canonical
        // merge — their `declare` flag is irrelevant there (the
        // canonical file IS the declared one)
        val canonicalInputs = inputs + manual +
                overrides.filter { it.output == null }
                    .map { Paths.get(it.path) }
        val outputs = mutableListOf<AccessOutput>()
        if (canonicalInputs.isNotEmpty()) {
            outputs += AccessOutput(
                canonical,
                convertMerge(canonicalInputs, format, mergedSources),
                declare = true
            )
        }
        // custom-output overrides emit independently — grouped so two
        // overrides to the same path merge like the canonical set does
        for ((out, specs) in overrides.filter { it.output != null }
            .groupBy { it.output!! }) {
            val files = specs.map { Paths.get(it.path) }
            outputs += AccessOutput(
                out,
                convertMerge(files, format, mergedSources),
                declare = specs.any { it.declare }
            )
        }
        return outputs
    }

    /** Per-file forced source formats from `accessFile(format=...)` —
     *  consulted by [convertMerge]'s source detection. */
    private val specSourceFormats =
        HashMap<java.nio.file.Path, AccessFormatConvert.Format>()

    /** Convert + merge one set of access inputs to the target format,
     *  recording every consumed source in [mergedSources]. */
    private fun convertMerge(
        files: List<Path>,
        format: AccessFormatConvert.Format,
        mergedSources: MutableSet<String>
    ): ByteArray {
        val devNs = Namespace(devNamespace.get())
        val tree by lazy(LazyThreadSafetyMode.NONE) {
            accessBundle.files.singleOrNull()?.let {
                SerializedMappingData.readEither(it.toPath())
                    .view<MappingTree>()
            }
        }
        val warn: (String) -> Unit = { logger.warn("[unimined] $it") }
        val converted = files.map { f ->
            val bytes = Files.readAllBytes(f)
            val src = specSourceFormats[
                f.toAbsolutePath().normalize()]
                ?: AccessFormatConvert.detect(f, bytes)
                ?: throw GradleException(
                    "env-declared access file '$f' isn't an AT/AW " +
                            "document (no recognized header/directive) — " +
                            "CT/classTweaker files don't convert; declare " +
                            "them via the classTweaker channel"
                )
            try {
                AccessFormatConvert.convert(
                    bytes, src, format,
                    tree = tree,
                    targetNs = devNs,
                    warn = warn
                )
            } catch (e: IllegalArgumentException) {
                if (tree == null &&
                    src != AccessFormatConvert.Format.AW &&
                    format == AccessFormatConvert.Format.AW
                ) {
                    throw GradleException(
                        "converting '$f' to AW needs the env's mapping " +
                                "tree for member descriptors — declare " +
                                "the file under `accessTransformers` so " +
                                "the wiring attaches the mapping bundle",
                        e
                    )
                }
                throw e
            }
        }

        val bytes = when (format) {
            AccessFormatConvert.Format.AW ->
                AccessFormatConvert.mergeAws(converted, devNs)
            else -> {
                // AT-family: parse each converted doc and emit the union —
                // ATData equality dedups cross-file repeats
                val reader = { b: ByteArray ->
                    StringCharReader(b.toString(Charsets.UTF_8))
                }
                val items = converted.flatMap {
                    if (format == AccessFormatConvert.Format.LEGACY_AT)
                        LegacyATReader.readData(reader(it), leinient = true)
                    else ATReader.readData(reader(it), leinient = true)
                }.distinct()
                val out = StringBuilder()
                if (format == AccessFormatConvert.Format.LEGACY_AT)
                    LegacyATWriter.writeData(items) { out.append(it) }
                else ATWriter.writeData(items) { out.append(it) }
                out.toString().toByteArray()
            }
        }

        for (f in files) {
            mergedSources += f.toAbsolutePath().toString()
        }
        return bytes
    }

    /** CT/AW/AT-looking files under the resource roots — hand-written
     *  access files that may already declare inject-interface rules. */
    private fun discoverAccessFiles(): List<java.io.File> {
        val out = mutableListOf<java.io.File>()
        val accessExts = setOf("ct", "classtweaker", "cfg", "accesswidener", "at")
        for (dir in resourceDirs.files) {
            if (!dir.isDirectory) continue
            java.nio.file.Files.walk(dir.toPath()).use { stream ->
                stream.filter { it.isRegularFile() }
                    .map { it.toFile() }
                    .filter { f -> f.name.lowercase().let { n ->
                        accessExts.any { n.endsWith(".$it") }
                    } }
                    .forEach { out += it }
            }
        }
        return out
    }

    /** Jar-relative path of the generated interface-injection CT. */
    private companion object {
        const val GENERATED_CT_PATH = "META-INF/unimined/injected-interfaces.ct"
    }

    private fun validate(meta: ModMetadata) {
        if (meta.sections.isEmpty() && mixinConfigs.get().isEmpty()) return
        val id = meta.id ?: throw GradleException(
            "mod { } declares metadata but no `id` — set `mod.id`"
        )
        require(id.matches(Regex("[a-z][a-z0-9_-]*"))) {
            "mod.id '$id' is invalid — must match [a-z][a-z0-9_-]*"
        }
    }

    /** `classTweaker v1 <dev ns>` + one `transitive-inject-interface`
     *  row per (target, interface) — sorted for deterministic output.
     *  Transitive so dependents' compile artifacts get the injection
     *  too (fabric-loader's own semantics for `loom:injected_interfaces`). */
    private fun classTweakerText(
        injections: Map<String, List<String>>,
        namespace: String
    ): ByteArray {
        val sb = StringBuilder("classTweaker\tv1\t$namespace\n")
        for ((target, ifaces) in injections.toSortedMap()) {
            for (iface in ifaces.sorted()) {
                sb.append("transitive-inject-interface\t$target\t$iface\n")
            }
        }
        return sb.toString().toByteArray()
    }

    private fun mixinJson(config: MixinConfigData): ByteArray = buildJsonObject {
        put("required", true)
        put("package", config.packageName)
        config.minVersion?.let { put("minVersion", it) }
        config.compatibilityLevel?.let { put("compatibilityLevel", it) }
        config.refmap?.let { put("refmap", it) }
        config.plugin?.let { put("plugin", it) }
        putJsonArray("mixins") { config.mixins.forEach { add(JsonPrimitive(it)) } }
        if (config.client.isNotEmpty()) {
            putJsonArray("client") { config.client.forEach { add(JsonPrimitive(it)) } }
        }
        if (config.server.isNotEmpty()) {
            putJsonArray("server") { config.server.forEach { add(JsonPrimitive(it)) } }
        }
    }.toString().toByteArray()

}
