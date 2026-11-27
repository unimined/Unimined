package xyz.wagyourtail.unimined.remapper.uml.nil

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.remapper.nil.NilFullTreeEmitter
import xyz.wagyourtail.unimined.mapping.remapper.nil.NilMappingsWriter
import xyz.wagyourtail.unimined.mapping.remapper.nil.NilUsageScanner
import xyz.wagyourtail.unimined.mapping.remapper
import xyz.wagyourtail.unimined.mapping.MappingTree
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Scans a nilmod's compiled classes and produces `META-INF/nil/mappings.json`
 * content: the dev→target reference usage tracked through bytecode refs,
 * `@Patch` annotations and string literals, plus the runtime access-widening
 * set. Keyed per target mapping-set id (`"default"` plus any additional
 * targets) — see NilGradle's `MappingDetector`/`TrackingLorenzRemapper`
 * design, reimplemented on the UML namespace-pair tree.
 *
 * Wire through `NilWiring` for `nil` environments; manual wiring:
 * - [classDirs]: the source-set output class dirs being scanned
 * - [scanClasspath]: compile classpath + class dirs (inheritance truth —
 *   must include the mod classes themselves or override resolution
 *   can't walk them)
 * - [accessTruthJars]: the pristine target-namespace artifact(s) — access
 *   truth for widen analysis (dev artifacts are pre-widened)
 * - [defaultBundle]: serialized `MappingData` for dev→prod
 * - [extraTargets]: additional target id → bundle
 */
@DisableCachingByDefault
abstract class NilMappingsScanTask : DefaultTask() {

    /** Mod compiled class directories — scanned for references. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ConfigurableFileCollection

    /** Inheritance scan input — compile classpath plus the class dirs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scanClasspath: ConfigurableFileCollection

    /** Pristine target-namespace jars for widen access truth; when empty
     *  the scan falls back to the dev-side (possibly pre-widened) scan. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val accessTruthJars: ConfigurableFileCollection

    /** Dev/compile namespace name — the json `from` side. */
    @get:Input
    abstract val devNamespace: Property<String>

    /** Serialized mapping bundle for the default target (dev→prod). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val defaultBundle: RegularFileProperty

    /** Additional target mapping-set id → serialized bundle. Not a
     *  file input itself — Gradle resolves annotated maps as raw
     *  notation — the files ride [extraTargetFiles] for dependency +
     *  content tracking; wire both together. */
    @get:Internal
    abstract val extraTargets: MapProperty<String, RegularFile>

    /** Target mapping-set id → target namespace name. Bundles carry the
     *  full chained tree (intermediate layers included), so the target
     *  side can't be inferred — wiring declares it. */
    @get:Input
    abstract val targetNamespaces: MapProperty<String, String>

    /** Input-file tracking for [extraTargets]' bundle files. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val extraTargetFiles: ConfigurableFileCollection

    /** Emit the full mapping tree instead of the tracked minimal scan —
     *  NilGradle `keepAllMappings` parity. */
    @get:Input
    abstract val keepAllMappings: Property<Boolean>

    /** Output `mappings.json` — the jar copy spec places it at
     *  `META-INF/nil/mappings.json`. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        keepAllMappings.convention(false)
        extraTargets.convention(emptyMap())
        // NilLoader's default Minecraft runtime target is the official
        // namespace; env wiring may overwrite this key when embedding the
        // task in a nonstandard product.
        targetNamespaces.convention(mapOf("default" to "official"))
        outputFile.convention(
            project.layout.buildDirectory.file("nilMappings/mappings.json")
        )
    }

    /**
     * Add one target mapping-set atomically. [bundle] is tracked both in
     * [extraTargets] (the scan input) and [extraTargetFiles] (Gradle's
     * file input/dependency fingerprint), while [targetNamespace] is the
     * required target-side namespace declaration. Wiring these three
     * channels separately is error-prone and previously the only API.
     */
    fun extraTarget(
        id: String,
        bundle: org.gradle.api.provider.Provider<RegularFile>,
        targetNamespace: String
    ) {
        extraTargets.put(id, bundle)
        extraTargetFiles.from(bundle)
        targetNamespaces.put(id, targetNamespace)
    }

    @get:Internal
    internal val classBytes: List<Pair<String, ByteArray>> by lazy {
        classDirs.files.filter { it.isDirectory }.flatMap { dir ->
            val root = dir.toPath()
            Files.walk(root).use { stream ->
                stream.filter {
                    it.isRegularFile() && it.toString().endsWith(".class")
                }.map {
                    root.relativize(it).joinToString("/") to it.readBytes()
                }.toList()
            }
        }
    }

    @TaskAction
    fun scan() {
        val devNs = Namespace(devNamespace.get())

        // inheritance truth: compile classpath + mod classes themselves
        val scanInputs = (scanClasspath.files + classDirs.files)
            .filter { it.exists() }.map { it.toPath() }.toSet()
        val inheritance = ASMInheritanceTree(devNs, ASMInheritanceTree.scan(scanInputs))

        // access truth: pristine target artifact when resolvable; the
        // scanner falls back to the dev-side scan otherwise
        val accessScan = accessTruthJars.files.filter { it.exists() }
            .map { it.toPath() }.toSet()
        val accessInheritance = if (accessScan.isNotEmpty()) {
            ASMInheritanceTree(Namespace("official"), ASMInheritanceTree.scan(accessScan))
        } else null

        // read class bytes once — each target scans the same input
        val classes = classBytes

        val targets = linkedMapOf<String, RegularFile>()
        targets["default"] = defaultBundle.get()
        targets.putAll(extraTargets.getOrElse(emptyMap()))

        val results = linkedMapOf<String, xyz.wagyourtail.unimined.mapping.remapper.nil.NilMappings>()
        for ((id, bundleFile) in targets) {
            val data = SerializedMappingData.read(bundleFile.asFile.toPath())
            val tree = data.view(MappingTree::class.java)
                ?: throw IllegalStateException(
                    "mapping bundle ${bundleFile.asFile} carries no MappingTree view"
                )
            val targetNsName = targetNamespaces.get()[id]
                ?: throw IllegalStateException(
                    "no targetNamespaces entry for target '$id' — wiring must declare it"
                )
            val targetNs = Namespace(targetNsName)
            val remapper = tree.remapper(devNs, targetNs)
            val scanner = NilUsageScanner(tree, remapper, inheritance, accessInheritance) { logger.info(it) }
            for ((_, bytes) in classes) scanner.scanClass(bytes)
            val scanned = scanner.result()
            results[id] = if (keepAllMappings.get()) {
                val full = NilFullTreeEmitter.emit(tree, devNs, targetNs)
                scanned.widenClasses.forEach { full.widenClasses += it }
                scanned.widenMethods.forEach { (o, s) ->
                    full.widenMethods.getOrPut(o) { java.util.TreeSet() } += s
                }
                scanned.widenFields.forEach { (o, s) ->
                    full.widenFields.getOrPut(o) { java.util.TreeSet() } += s
                }
                full
            } else scanned
        }

        val out = outputFile.get().asFile.toPath()
        Files.createDirectories(out.parent)
        Files.write(out, NilMappingsWriter.write(results).toByteArray(Charsets.UTF_8))
        logger.lifecycle(
            "[NilMappingsScan] wrote $out — ${results.values.sumOf { it.classes.size }} classes, " +
                "${results.values.sumOf { it.widenClasses.size }} widened classes"
        )
    }

}
