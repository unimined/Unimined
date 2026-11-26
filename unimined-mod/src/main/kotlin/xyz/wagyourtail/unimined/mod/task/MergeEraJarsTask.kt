package xyz.wagyourtail.unimined.mod.task

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.mod.AdditionalNestedJarInput
import xyz.wagyourtail.unimined.mod.EraJarInput
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile

/**
 * Merges era production artifacts into one distribution jar. This is the
 * shared machinery behind `mergedEraJar` (single-loader multi-version) and
 * `chimeraJar` (multi-loader): flattened era entries land in the outer jar,
 * nested era entries are merged first into a generated inner jar at
 * [nestedPath], and duplicate jar-relative paths are first-wins.
 *
 * `combineWith` output is deliberately prepended as raw source-set output,
 * not as another remapped era artifact: shared code is the merge anchor, so
 * era jars produced with shadow-style composition can have their embedded
 * copies deduplicated away. A future auto-divergence pass (activation stubs /
 * cross-era conflict resolution) belongs here, but is explicitly out of v1
 * scope — see the JarMerger failure analysis before designing that feature.
 */
@DisableCachingByDefault
abstract class MergeEraJarsTask : DefaultTask() {

    /** Archive base name, matching the standard Gradle archive-task vocabulary. */
    @get:Input
    abstract val archiveBaseName: Property<String>

    /** Optional archive appendix. */
    @get:Input
    @get:Optional
    abstract val archiveAppendix: Property<String>

    /** Optional archive version component. */
    @get:Input
    @get:Optional
    abstract val archiveVersion: Property<String>

    /** Optional archive classifier component. */
    @get:Input
    @get:Optional
    abstract val archiveClassifier: Property<String>

    /** Archive extension; defaults to `jar`. */
    @get:Input
    abstract val archiveExtension: Property<String>

    /** Final archive file name. */
    @get:Input
    abstract val archiveFileName: Property<String>

    /** Archive destination directory; [archiveFile] is the tracked output. */
    @get:Internal
    abstract val destinationDirectory: DirectoryProperty

    /** Final archive output. */
    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    /** Combined source-set output roots, declared for up-to-date checking. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedOutputs: ConfigurableFileCollection

    /** Ordered absolute paths of [sharedOutputs] as they participate in the merge. */
    @get:Input
    abstract val sharedOutputPaths: ListProperty<String>

    /** Era artifact files, declared separately so task-output producers are inputs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val eraJarFiles: ConfigurableFileCollection

    /** Ordered era rows. */
    @get:Input
    abstract val eraInputs: ListProperty<EraJarInput>

    /** Source files for physically embedded additional nested jars. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalNestedJarFiles: ConfigurableFileCollection

    /** Target/source rows for physically embedded additional nested jars. */
    @get:Input
    abstract val additionalNestedInputs: ListProperty<AdditionalNestedJarInput>

    /** Whether [sharedOutputPaths] participate before era artifacts. */
    @get:Input
    abstract val mainFirst: Property<Boolean>

    /** `warn`, `strict`, or `off`. */
    @get:Input
    abstract val overlapValidation: Property<String>

    /** Outer-jar path of the generated nested jar. */
    @get:Input
    abstract val nestedPath: Property<String>

    /** Whether a shell `fabric.mod.json` is generated inside the nested jar. */
    @get:Input
    abstract val emitNestedDescriptor: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val nestedId: Property<String>

    @get:Input
    @get:Optional
    abstract val nestedParent: Property<String>

    @get:Input
    @get:Optional
    abstract val nestedVersion: Property<String>

    /** Additional entries for the shell descriptor's `jars[]`. */
    @get:Input
    abstract val nestedEntries: ListProperty<String>

    /** Outer manifest attributes; these override merged input attributes. */
    @get:Input
    abstract val manifestAttributes: MapProperty<String, String>

    /** Nested manifest attributes; these override merged nested-input attributes. */
    @get:Input
    abstract val nestedManifestAttributes: MapProperty<String, String>

    init {
        archiveExtension.convention("jar")
        archiveFile.convention(
            archiveFileName.flatMap { fileName ->
                destinationDirectory.map { it.file(fileName) }
            }
        )
        mainFirst.convention(true)
        overlapValidation.convention("warn")
        nestedPath.convention("META-INF/jars/nested.jar")
        emitNestedDescriptor.convention(true)
    }

    private enum class Target { OUTER, INNER }

    private data class Overlap(
        val path: String,
        val first: String,
        val duplicate: String
    )

    /** Merge the inputs. The implementation deliberately scans zip central
     *  directories before writing so strict validation fails before a partial
     *  output is produced. */
    @TaskAction
    fun merge() {
        val validation = overlapValidation.get().lowercase()
        if (validation !in setOf("warn", "strict", "off")) {
            throw GradleException(
                "unimined: ${name}: invalid overlapValidation " +
                    "'${overlapValidation.get()}' — expected one of: warn, strict, off"
            )
        }

        val eras = eraInputs.get()
        eras.forEach { row ->
            if (row.partition !in setOf("nest", "flatten")) {
                throw GradleException(
                    "unimined: ${name}: invalid partition '${row.partition}' for era " +
                        "'${row.name}' — expected one of: nest, flatten"
                )
            }
        }

        val output = archiveFile.get().asFile
        val nestedPath = normalizeEntryPath(nestedPath.get())
        if (nestedPath.isEmpty() || nestedPath.endsWith("/")) {
            throw GradleException("unimined: ${name}: nested path must be a jar file path")
        }
        val hasNested = eras.any { it.partition == "nest" }

        val sharedRoots = orderedUniqueFiles(sharedOutputPaths.get())
        val eraFiles = eras.map { row ->
            File(row.artifactPath).also {
                if (!it.isFile) throw GradleException(
                    "unimined: ${name}: artifact for era '${row.name}' does not exist: $it"
                )
            }
        }

        // first-wins ownership per target. Generated nested metadata is a
        // reserved winner: era descriptors are intentionally replaced by the
        // shell descriptor, so those collisions do not count as user-facing
        // overlap diagnostics.
        val outerWinners = linkedMapOf<String, String>()
        val innerWinners = linkedMapOf<String, String>()
        val sharedPaths = linkedSetOf<String>()
        val overlaps = mutableListOf<Overlap>()

        fun addOverlap(path: String, first: String, duplicate: String) {
            overlaps += Overlap(path, first, duplicate)
        }

        if (mainFirst.get()) {
            sharedRoots.forEachIndexed { index, root ->
                if (!root.exists()) return@forEachIndexed
                val source = "shared output #${index + 1} (${root.absolutePath})"
                entriesForRoot(root).forEach { path ->
                    if (isManifest(path)) return@forEach
                    sharedPaths += path
                    val existing = outerWinners[path]
                    if (existing == null) {
                        outerWinners[path] = "shared:$index"
                    } else if (existing != "shared:$index") {
                        addOverlap(path, existing, source)
                    }
                }
            }
        }

        if (hasNested) {
            if (emitNestedDescriptor.get()) {
                innerWinners["fabric.mod.json"] = "generated:descriptor"
            }
            additionalNestedInputs.get().forEach { row ->
                val target = normalizeEntryPath(row.targetPath)
                innerWinners.putIfAbsent(target, "additional:$target")
            }
        }

        eras.forEachIndexed { index, row ->
            val target = if (row.partition == "nest") Target.INNER else Target.OUTER
            val winnerMap = if (target == Target.INNER) innerWinners else outerWinners
            val source = "era '${row.name}'"
            entriesForZip(eraFiles[index], row.name).forEach { path ->
                if (isManifest(path)) return@forEach
                if (path in sharedPaths) {
                    addOverlap(path, "combined output", source)
                    return@forEach
                }
                if (target == Target.OUTER && hasNested && path == nestedPath) {
                    addOverlap(path, "generated nested jar", source)
                    return@forEach
                }
                val existing = winnerMap[path]
                when {
                    existing == null -> winnerMap[path] = "era:$index"
                    existing.startsWith("generated:") || existing.startsWith("additional:") -> {
                        // intentional replacement — the generated shell/additional
                        // nested library is authoritative, not a divergence warning
                    }
                    else -> addOverlap(path, existing, source)
                }
            }
        }

        if (overlaps.isNotEmpty() && validation != "off") {
            val shown = overlaps.take(20).joinToString("\n") {
                "  ${it.path}: ${it.duplicate} duplicates ${it.first}"
            } + if (overlaps.size > 20) "\n  ... ${overlaps.size - 20} more" else ""
            val message = "unimined: ${name}: ${overlaps.size} overlapping jar " +
                "entries; first writer wins:\n$shown"
            if (validation == "strict") throw GradleException(message)
            logger.warn(message)
        }

        val outerManifest = mergeManifestAttributes(
            (if (mainFirst.get()) sharedRoots.mapNotNull { manifestForRoot(it) } else emptyList()) +
                eras.mapIndexedNotNull { index, row ->
                    if (row.partition == "flatten") manifestForZip(eraFiles[index]) else null
                },
            manifestAttributes.get()
        )
        val innerManifest = mergeManifestAttributes(
            eras.mapIndexedNotNull { index, row ->
                if (row.partition == "nest") manifestForZip(eraFiles[index]) else null
            },
            nestedManifestAttributes.get()
        )

        val innerFile = if (hasNested) {
            temporaryDir.resolve("nested-era-inner.jar").also {
                it.parentFile.mkdirs()
                it.delete()
            }
        } else null

        if (hasNested && innerFile != null) {
            writeNestedJar(innerFile, innerManifest, eras, eraFiles, innerWinners)
        }

        output.parentFile.mkdirs()
        if (output.exists()) output.delete()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            writeManifest(zip, outerManifest)
            if (mainFirst.get()) {
                sharedRoots.forEachIndexed { index, root ->
                    copyWinningRootEntries(root, "shared:$index", outerWinners, zip)
                }
            }
            eras.forEachIndexed { index, row ->
                if (row.partition == "flatten") {
                    copyWinningZipEntries(eraFiles[index], "era:$index", outerWinners, zip)
                }
            }
            if (hasNested && innerFile != null) {
                writeEntry(zip, nestedPath)
                BufferedInputStream(FileInputStream(innerFile)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeNestedJar(
        output: File,
        manifest: Manifest,
        eras: List<EraJarInput>,
        eraFiles: List<File>,
        innerWinners: Map<String, String>
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            writeManifest(zip, manifest)
            if (emitNestedDescriptor.get()) {
                writeEntry(zip, "fabric.mod.json")
                zip.write(shellDescriptor().toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val writtenAdditional = mutableSetOf<String>()
            additionalNestedInputs.get().forEach { row ->
                val target = normalizeEntryPath(row.targetPath)
                if (!writtenAdditional.add(target)) return@forEach
                val source = row.sourcePaths.map(::File).firstOrNull { it.isFile }
                    ?: throw GradleException(
                        "unimined: ${name}: no existing source for additional nested jar '$target'"
                    )
                writeEntry(zip, target)
                BufferedInputStream(FileInputStream(source)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            eras.forEachIndexed { index, row ->
                if (row.partition == "nest") {
                    copyWinningZipEntries(eraFiles[index], "era:$index", innerWinners, zip)
                }
            }
        }
    }

    private fun shellDescriptor(): kotlinx.serialization.json.JsonObject {
        val id = nestedId.orNull ?: throw GradleException(
            "unimined: ${name}: nested descriptor id is required when nested eras are present"
        )
        val version = nestedVersion.orNull ?: throw GradleException(
            "unimined: ${name}: nested descriptor version is required when nested eras are present"
        )
        return buildJsonObject {
            put("schemaVersion", 1)
            put("id", id)
            put("version", version)
            nestedParent.orNull?.let { parent ->
                putJsonObject("custom") {
                    putJsonObject("modmenu") {
                        putJsonObject("parent") {
                            put("id", parent)
                        }
                    }
                }
            }
            putJsonArray("jars") {
                nestedEntries.get().distinct().forEach { entryPath ->
                    add(buildJsonObject { put("file", entryPath) })
                }
            }
        }
    }

    private fun mergeManifestAttributes(
        inputs: List<Map<String, String>>,
        overrides: Map<String, String>
    ): Manifest {
        val merged = linkedMapOf<String, String>()
        // Decision: duplicate input attributes are first-wins, like entries.
        // Manifest has no generic multi-value grammar, so unimined does not
        // join values with loader-specific separators; spec attributes are
        // the explicit override and always win.
        inputs.forEach { attrs ->
            attrs.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        }
        overrides.forEach { (key, value) -> merged[key] = value }
        val manifest = Manifest()
        val main = manifest.mainAttributes
        main[Attributes.Name.MANIFEST_VERSION] = "1.0"
        merged.toSortedMap().forEach { (key, value) ->
            if (!key.equals("Manifest-Version", ignoreCase = true)) main.putValue(key, value)
        }
        return manifest
    }

    private fun manifestForRoot(root: File): Map<String, String>? {
        if (!root.exists()) return null
        if (root.isDirectory) {
            val direct = root.resolve("META-INF/MANIFEST.MF")
            val lower = root.resolve("META-INF/manifest.mf")
            val file = when {
                direct.isFile -> direct
                lower.isFile -> lower
                else -> return null
            }
            return readManifest(file.inputStream())?.let { manifestAttributes(it) }
        }
        return manifestForZip(root)
    }

    private fun manifestForZip(file: File): Map<String, String>? {
        if (!file.isFile || !isZipName(file.name)) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { isManifest(it.name) }
                    ?: return@use null
                zip.getInputStream(entry).use { input ->
                    readManifest(input)?.let { manifestAttributes(it) }
                }
            }
        }.getOrNull()
    }

    private fun readManifest(input: InputStream): Manifest? = runCatching {
        Manifest(input)
    }.getOrNull()

    private fun manifestAttributes(manifest: Manifest): Map<String, String> =
        manifest.mainAttributes.entries.associate { (key, value) ->
            key.toString() to value.toString()
        }

    private fun entriesForRoot(root: File): List<String> {
        if (!root.exists()) return emptyList()
        if (root.isDirectory) {
            val base = root.toPath()
            Files.walk(base).use { stream ->
                return stream.filter { it.isRegularFile() }
                    .map { normalizeEntryPath(base.relativize(it).toString()) }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toList()
            }
        }
        if (isZipName(root.name)) return entriesForZip(root, root.name)
        return listOf(normalizeEntryPath(root.name))
    }

    private fun entriesForZip(file: File, owner: String): List<String> {
        try {
            ZipFile(file).use { zip ->
                return zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { normalizeEntryPath(it.name) }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        } catch (e: Exception) {
            throw GradleException(
                "unimined: ${name}: artifact for era '$owner' is not a readable zip/jar: $file", e
            )
        }
    }

    private fun copyWinningRootEntries(
        root: File,
        sourceId: String,
        winners: Map<String, String>,
        zip: ZipOutputStream
    ) {
        if (!root.exists()) return
        if (root.isDirectory) {
            val base = root.toPath()
            Files.walk(base).use { stream ->
                stream.filter { it.isRegularFile() }
                    .map { it to normalizeEntryPath(base.relativize(it).toString()) }
                    .toList()
                    .sortedBy { it.second }
                    .forEach { (path, entryPath) ->
                        if (!isManifest(entryPath) && winners[entryPath] == sourceId) {
                            writeEntry(zip, entryPath)
                            Files.newInputStream(path).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
            }
        } else if (isZipName(root.name)) {
            copyWinningZipEntries(root, sourceId, winners, zip)
        } else {
            val entryPath = normalizeEntryPath(root.name)
            if (!isManifest(entryPath) && winners[entryPath] == sourceId) {
                writeEntry(zip, entryPath)
                BufferedInputStream(FileInputStream(root)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun copyWinningZipEntries(
        file: File,
        sourceId: String,
        winners: Map<String, String>,
        zip: ZipOutputStream
    ) {
        ZipFile(file).use { input ->
            input.entries().asSequence()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    val path = normalizeEntryPath(entry.name)
                    if (!isManifest(path) && winners[path] == sourceId) {
                        writeEntry(zip, path)
                        input.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
        }
    }

    private fun writeManifest(zip: ZipOutputStream, manifest: Manifest) {
        writeEntry(zip, "META-INF/MANIFEST.MF")
        manifest.write(zip)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, path: String) {
        val entry = ZipEntry(normalizeEntryPath(path))
        entry.time = FIXED_ZIP_TIME
        zip.putNextEntry(entry)
    }

    private fun orderedUniqueFiles(paths: List<String>): List<File> {
        val seen = linkedSetOf<String>()
        return paths.map(::File).filter {
            seen.add(it.absoluteFile.normalize().absolutePath)
        }
    }

    private fun normalizeEntryPath(path: String): String =
        path.replace('\\', '/').removePrefix("/")

    private fun isManifest(path: String): Boolean =
        normalizeEntryPath(path).equals("META-INF/MANIFEST.MF", ignoreCase = true)

    private fun isZipName(name: String): Boolean =
        name.endsWith(".jar", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)

    private companion object {
        /** 1980-01-01, the earliest portable zip timestamp — deterministic output. */
        const val FIXED_ZIP_TIME: Long = 315532800000L
    }

}
