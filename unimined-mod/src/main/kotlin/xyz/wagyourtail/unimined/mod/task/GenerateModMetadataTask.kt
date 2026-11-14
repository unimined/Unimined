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
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
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

    /** The resolved *named* namespaces of the environment's mapping
     *  data — fed to [ModMetadataEmitter.EmitContext.namedNamespaces]. */
    @get:Input
    abstract val namedNamespaces: SetProperty<String>

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

    /** Interface injections declared on the env (`access {
     *  injectInterface(...) }` + `interfaceInjection(...)`) — target
     *  internal name → interface internal names in the dev namespace.
     *  Carried into `loom:injected_interfaces`-style metadata on every
     *  loader (IDE/dependents' compile surface); on CT-capable loaders
     *  the canonical access doc already carries the runtime form. */
    @get:Input
    abstract val declaredInterfaceInjections: MapProperty<String, List<String>>

    /** Env `discoverMixinConfigs` flag — `*.mixins.json` files in
     *  resources auto-fill the `mixins` array. */
    @get:Input
    abstract val discoverMixinConfigs: Property<Boolean>

    /** Env `discoverGametestEntrypoints` flag — compiled classes are
     *  scanned for loader gametest interfaces/annotations. */
    @get:Input
    abstract val discoverGametestEntrypoints: Property<Boolean>

    /** Env `interfaceInjectionScan` flag — compiled mixin classes are
     *  scanned for implemented interfaces (opt-in; `access {
     *  injectInterface(...) }` is the always-on declaration path). */
    @get:Input
    abstract val interfaceInjectionScan: Property<Boolean>

    /** Env `accessFileName` — overrides the emitter's conventional
     *  canonical access-doc path where the loader declares it in
     *  metadata (`{modid}` expands). `null` keeps the convention. */
    @get:Input
    @get:Optional
    abstract val accessPathOverride: Property<String>

    /** Env `access { format }` — overrides the emitted access doc's
     *  format (default `emitter.accessFormat`, the loader's consumed
     *  one). `null` keeps the loader default. */
    @get:Input
    @get:Optional
    abstract val accessFormatOverride: Property<String>

    init {
        // defaults match the env conventions — packaging wiring always
        // `set`s from the env, these keep direct task construction sane
        declaredInterfaceInjections.convention(emptyMap())
        discoverMixinConfigs.convention(true)
        discoverGametestEntrypoints.convention(true)
        interfaceInjectionScan.convention(false)
    }

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
        // owns namespace translation on remapping envs. Conversion runs
        // after the discovery scans so scan-derived interface
        // injections can merge into a CT-capable canonical doc
        var accessFile: String? = null
        var accessOutputs = emptyList<AccessOutput>()

        // loader envs emit env-fact manifest attrs unconditionally —
        // they describe the environment, not the mod's identity; the
        // metadata documents still require `id` — an unconfigured
        // `mod {}` emits nothing so a manual metadata file stays
        // authoritative
        if (loader != null) {
            val emitter = ModMetadataEmitterRegistry().select(loader)

            // hand-written mixin configs ship as resources but nothing
            // references them — surface them to the emitter (feeds
            // `MixinConfigs` manifest attr even without mod {} DSL)
            val generatedNames = mixins.mapTo(HashSet()) { it.fileName }
            val discovered = if (discoverMixinConfigs.get()) {
                ModMetadataScan.discoverMixinConfigs(
                    resourceDirs.files, exclude = generatedNames
                )
            } else emptyList()

            // env-declared II rules ride `loom:injected_interfaces`-
            // style metadata on every loader; the opt-in class scan
            // unions in on top
            var scanInjections = emptyMap<String, List<String>>()
            var gametestEntrypoints = emptyList<String>()
            var clientGametestEntrypoints = emptyList<String>()

            if (meta.id != null) {
                // class-dir scans only matter for emitted docs — the
                // access doc's fmj declaration needs emit()
                val classDirs = classesDirs.files
                if (interfaceInjectionScan.get() && classDirs.isNotEmpty()) {
                    val mixinClassNames = mixins.flatMap { cfg ->
                        (cfg.mixins + cfg.client + cfg.server)
                            .map { "${cfg.packageName}.$it" }
                    } + discovered.flatMap { it.second.classes }
                    if (mixinClassNames.isNotEmpty()) {
                        val existing = ModMetadataScan.existingInterfaceRules(
                            declaredAccessFiles.files + discoverAccessFiles()
                        )
                        scanInjections = ModMetadataScan.interfaceInjections(
                            mixinClassNames, classDirs, existing
                        )
                    }
                }
                if (discoverGametestEntrypoints.get()) {
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
                }
            }

            val interfaceInjections =
                declaredInterfaceInjections.get().toMutableMap().apply {
                    scanInjections.forEach { (target, ifaces) ->
                        merge(target, ifaces) { a, b -> (a + b).distinct() }
                    }
                }

            // scan-derived II rules additionally merge into the
            // canonical access doc as CT rows when the loader version
            // can parse them — declared rules already ride their own
            // generated doc through declaredAccessFiles
            accessOutputs = convertDeclaredAccess(
                emitter, meta.id, mergedSources,
                extraCtRows = scanInjections.flatMap { (target, ifaces) ->
                    ifaces.map { "transitive-inject-interface\t$target\t$it" }
                }
            )
            // metadata declares the first emitted file — the canonical
            // output when the default merge produced one
            accessFile = accessOutputs.firstOrNull { it.declare }?.path

            val ctx = ModMetadataEmitter.EmitContext(
                metadata = meta,
                mixinFiles = mixins.map { it.fileName },
                discoveredMixinConfigs = discovered.map { it.first },
                gametestEntrypoints = gametestEntrypoints,
                clientGametestEntrypoints = clientGametestEntrypoints,
                interfaceInjections = interfaceInjections,
                accessFile = accessFile,
                accessFiles = accessOutputs.map { it.path },
                environmentId = environmentId.get(),
                minecraftVersion = minecraftVersion.get(),
                loaderVersion = loaderVersion.orNull,
                manifestAttributes = manifestAttributes.get(),
                side = side.get(),
                devNamespace = devNamespace.get(),
                prodNamespaces = prodNamespaces.get(),
                namedNamespaces = namedNamespaces.get()
            )
            emitter.emitManifest(ctx)?.let {
                emitted[ModMetadataEmitter.MANIFEST_PATH] = it
            }
            if (meta.id != null) {
                emitted += emitter.emit(ctx)
            }
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
            stampUniminedMarkers(manifest)
        )

        // dev runs scan exploded classpath dirs, not jars — FML (modern
        // SecureJar and legacy CoreModManager alike) parses
        // META-INF/MANIFEST.MF inside classpath directories, so the
        // emitted manifest body also lands in generated resources where
        // runClient/runServer see FMLModType/MixinConfigs/FMLAT. Jar-side
        // the same body rides manifestFile -> jar.manifest merge; the
        // packaging wiring excludes this file copy from the packed jar.
        val out = outputDir.get().asFile.toPath()
        if (manifest != null) {
            val manualMf = resourceDirs.files.mapNotNull { dir ->
                dir.toPath().resolve(ModMetadataEmitter.MANIFEST_PATH)
                    .takeIf(Files::isRegularFile)
            }
            var bytes = stampUniminedMarkers(manifest)
            if (manualMf.isNotEmpty()) {
                val mf = java.util.jar.Manifest(
                    java.io.ByteArrayInputStream(bytes))
                for (f in manualMf) {
                    mf.mainAttributes.putAll(
                        Files.newInputStream(f).use {
                            java.util.jar.Manifest(it).mainAttributes
                        })
                    mergedSources += f.toAbsolutePath().toString()
                }
                // generated (DSL) attrs stay authoritative over manual
                mf.mainAttributes.putAll(java.util.jar.Manifest(
                    java.io.ByteArrayInputStream(manifest)).mainAttributes)
                bytes = java.io.ByteArrayOutputStream()
                    .also { mf.write(it) }.toByteArray()
            }
            val target = out.resolve(ModMetadataEmitter.MANIFEST_PATH)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        // merge emitted files into matching manual resources; record
        // every merged source path so processResources drops them
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
        mergedSources: MutableSet<String>,
        extraCtRows: List<String> = emptyList()
    ): List<AccessOutput> {
        val format = accessFormatOverride.orNull?.let { name ->
            AccessFormatConvert.Format.byName(name)
                ?: throw GradleException(
                    "access { format = '$name' } isn't a known access " +
                            "format (aw, at, legacy_at)")
        } ?: emitter.accessFormat
            ?.let(AccessFormatConvert.Format::byName)
        val pathTemplate = emitter.accessPath
        if (format == null || pathTemplate.isNullOrEmpty()) {
            if (accessPathOverride.isPresent ||
                accessFormatOverride.isPresent
            ) {
                logger.warn(
                    "[unimined] accessFileName/access { format } " +
                            "ignored — loader has no access-file " +
                            "convention"
                )
            }
            return emptyList()
        }
        if (accessFormatOverride.isPresent &&
            accessFormatOverride.get() != emitter.accessFormat
        ) {
            logger.warn(
                "[unimined] access doc emits as '${format.name}' " +
                        "instead of the loader's conventional " +
                        "'${emitter.accessFormat}' — ensure the " +
                        "loader parses it at that path"
            )
        }
        // whether the canonical doc may carry classTweaker directives —
        // fabric >= 0.18 parses CT content through `accessWidener`
        val ctCapable = emitter.accessDocCtCapable(loaderVersion.orNull)

        val overrides = customAccessFiles.get()
        val canonical = (accessPathOverride.orNull ?: pathTemplate)
            .replace("{modid}", modId ?: "mod")
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
        if (canonicalInputs.isNotEmpty() || extraCtRows.isNotEmpty()) {
            val bytes = convertMerge(
                canonicalInputs, format, mergedSources,
                ctCapable, extraCtRows
            )
            // a CT-row-only merge on a non-CT loader drops every rule —
            // no point shipping a header-only file
            if (canonicalInputs.isNotEmpty() || ctCapable) {
                outputs += AccessOutput(canonical, bytes, declare = true)
            }
        }
        // custom-output overrides emit independently — grouped so two
        // overrides to the same path merge like the canonical set does
        for ((out, specs) in overrides.filter { it.output != null }
            .groupBy { it.output!! }) {
            val files = specs.map { Paths.get(it.path) }
            outputs += AccessOutput(
                out,
                convertMerge(files, format, mergedSources, ctCapable),
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
     *  recording every consumed source in [mergedSources].
     *  `classTweaker`-format inputs split into their AW-shapeable
     *  access directives (merged normally) and CT-only directives —
     *  which join [extraCtRows] verbatim: on a [ctCapable] AW-family
     *  canonical doc they emit as-is (the doc upgrades to
     *  `classTweaker`); anywhere else they drop with a warning. */
    private fun convertMerge(
        files: List<Path>,
        format: AccessFormatConvert.Format,
        mergedSources: MutableSet<String>,
        ctCapable: Boolean,
        extraCtRows: List<String> = emptyList()
    ): ByteArray {
        val devNs = Namespace(devNamespace.get())
        val tree by lazy(LazyThreadSafetyMode.NONE) {
            accessBundle.files.singleOrNull()?.let {
                SerializedMappingData.readEither(it.toPath())
                    .view<MappingTree>()
            }
        }
        val warn: (String) -> Unit = { logger.warn("[unimined] $it") }
        val ctRows = extraCtRows.toMutableList()
        val converted = files.map { f ->
            val bytes = Files.readAllBytes(f)
            var src = specSourceFormats[
                f.toAbsolutePath().normalize()]
                ?: AccessFormatConvert.detect(f, bytes)
            var doc = bytes
            if (src == null && isClassTweakerDoc(bytes)) {
                // CT doc — access directives ride the AW merge; CT-only
                // directives accumulate for the emit decision below
                doc = ctDocToAw(bytes, ctRows)
                    ?: throw GradleException(
                        "access file '$f' has a classTweaker header but " +
                                "malformed content"
                    )
                src = AccessFormatConvert.Format.AW
            }
            src ?: throw GradleException(
                "env-declared access file '$f' isn't an AT/AW/CT " +
                        "document (no recognized header/directive)"
            )
            try {
                AccessFormatConvert.convert(
                    doc, src, format,
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
            // header carries the loader-facing canonical label
            // (`named` for named-role dev columns like `mojmap`);
            // members were already resolved to the dev column above
            AccessFormatConvert.Format.AW -> {
                val merged = AccessFormatConvert.mergeAws(
                    converted,
                    Namespace(
                        FabricNamespaces.canonical(
                            devNs.name, namedNamespaces.get()
                        )
                    )
                )
                when {
                    ctRows.isEmpty() -> merged
                    ctCapable -> upgradeToCtDoc(
                        merged, ctRows,
                        FabricNamespaces.canonical(
                            devNs.name, namedNamespaces.get()
                        )
                    )
                    else -> {
                        ctRows.distinct().forEach {
                            warn(
                                "dropping classTweaker directive — " +
                                        "loader can't parse CT-format " +
                                        "access docs: $it"
                            )
                        }
                        merged
                    }
                }
            }
            else -> {
                // AT-family: parse each converted doc and emit the union —
                // ATData equality dedups cross-file repeats; CT-only
                // directives have no AT analog
                ctRows.distinct().forEach {
                    warn("dropping classTweaker directive (no AT analog): $it")
                }
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

    /** `classTweaker` directives AW can't express — emitted verbatim on
     *  CT-capable docs, dropped elsewhere. */
    private val ctOnlyDirectives = setOf(
        "inject-interface", "transitive-inject-interface",
        "extend-enum", "transitive-extend-enum"
    )

    /** Whether [bytes] is a `classTweaker` document (header line). */
    private fun isClassTweakerDoc(bytes: ByteArray): Boolean =
        bytes.toString(Charsets.UTF_8).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.startsWith("classTweaker") == true

    /**
     * Split a `classTweaker` doc: CT-only directives append to
     * [ctRows]; the rest re-emits as an `accessWidener` doc under the
     * same namespace token so it flows through the AW merge path.
     */
    private fun ctDocToAw(
        bytes: ByteArray,
        ctRows: MutableList<String>
    ): ByteArray? {
        val text = bytes.toString(Charsets.UTF_8)
        val content = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val header = content.firstOrNull()
            ?.takeIf { it.startsWith("classTweaker") } ?: return null
        val ns = header.split(Regex("\\s+")).getOrNull(2) ?: "named"
        val keep = content.drop(1).filter { line ->
            val directive = line.substringBefore('\t').substringBefore(' ')
            if (directive in ctOnlyDirectives) {
                ctRows += line
                false
            } else true
        }
        return (listOf("accessWidener\tv1\t$ns") + keep)
            .joinToString("\n", postfix = "\n").toByteArray()
    }

    /** Re-emit a merged AW doc as `classTweaker` (v2 when `extend-enum`
     *  rules are present — fabric requires the v2 header + loader
     *  0.19.0 for them), with the CT directives appended. */
    private fun upgradeToCtDoc(
        aw: ByteArray,
        ctRows: List<String>,
        ns: String
    ): ByteArray {
        val body = aw.toString(Charsets.UTF_8).lines()
            .filterIndexed { i, l -> i > 0 || !l.startsWith("accessWidener") }
            .filter { it.isNotBlank() }
        val needsV2 = ctRows.any {
            it.substringBefore('\t').substringBefore(' ')
                .removePrefix("transitive-") == "extend-enum"
        }
        return (listOf(
            "classTweaker\t${if (needsV2) "v2" else "v1"}\t$ns"
        ) + body + ctRows.distinct().sorted())
            .joinToString("\n", postfix = "\n").toByteArray()
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

    private fun validate(meta: ModMetadata) {
        if (meta.sections.isEmpty() && mixinConfigs.get().isEmpty()) return
        val id = meta.id ?: throw GradleException(
            "mod { } declares metadata but no `id` — set `mod.id`"
        )
        require(id.matches(Regex("[a-z][a-z0-9_-]*"))) {
            "mod.id '$id' is invalid — must match [a-z][a-z0-9_-]*"
        }
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

    /** Stamp unimined's provenance marker into a manifest body —
     *  `Unimined-Version` identifies both that the jar came through
     *  unimined's packaging and which release built it (the
     *  `Fabric-Loom-Version` equivalent). `null` yields a bare
     *  stamped manifest; unparseable bodies pass through unstamped.
     *  The version resolves from this jar's `Implementation-Version`
     *  (stamped by the build), `dev` when absent (tests). */
    private fun stampUniminedMarkers(manifest: ByteArray?): ByteArray {
        val mf = if (manifest == null) {
            java.util.jar.Manifest().apply {
                mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            }
        } else {
            runCatching {
                java.util.jar.Manifest(java.io.ByteArrayInputStream(manifest))
            }.getOrNull() ?: return manifest
        }
        mf.mainAttributes.putValue(
            "Unimined-Version",
            javaClass.`package`?.implementationVersion ?: "dev")
        return java.io.ByteArrayOutputStream()
            .also { mf.write(it) }.toByteArray()
    }

}
