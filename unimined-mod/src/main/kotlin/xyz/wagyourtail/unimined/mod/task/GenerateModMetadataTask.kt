package xyz.wagyourtail.unimined.mod.task

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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.mod.MixinConfigData
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.ModMetadataEmitterRegistry
import xyz.wagyourtail.unimined.mod.internal.MetadataJsonMerge
import xyz.wagyourtail.unimined.mod.internal.ModMetadataScan
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.nio.file.Files
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
@CacheableTask
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
     *  `inject-interface` rules so generation never duplicates them. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredAccessFiles: ConfigurableFileCollection

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
                    classDirs, iface.replace('.', '/')
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
                    environmentId = environmentId.get(),
                    minecraftVersion = minecraftVersion.get(),
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
        val mergedSources = LinkedHashSet<String>()
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

        Files.createDirectories(
            mergedPathsFile.get().asFile.toPath().parent
        )
        Files.write(
            mergedPathsFile.get().asFile.toPath(),
            mergedSources.joinToString("\n").toByteArray()
        )
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
