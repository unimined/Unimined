package xyz.wagyourtail.unimined.remap.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.wagyourtail.commonskt.jvm.moveAtomicTo
import xyz.wagyourtail.commonskt.jvm.tempSibling
import xyz.wagyourtail.unimined.api.util.MavenCoords
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * Jar-in-jar embedding — the `nestedJars` PROD_REPACK mechanism
 * (`include`/`nestJars` bucket → `META-INF/jars|jarjar/` entries plus
 * loader metadata). Runs as a post-pass on the *produced* jar — after
 * the JAR pipeline inside `RemapJarTask.copy()`, and as a `doLast` on
 * the plain `Jar` task for identity environments whose remap hop
 * collapses — so non-remapping environments get identical treatment.
 *
 * Two formats:
 * - `fabric` — `META-INF/jars/<file>` plus a `{file}` entry under the
 *   metadata key (`jars` for `fabric.mod.json`, `quilt_loader.jars`
 *   for `quilt.mod.json`); nested jars missing the loader metadata
 *   file get a synthesized stub (loom/1.x `includeCache` parity).
 * - `jarjar` — `META-INF/jarjar/<file>` plus `META-INF/jarjar/
 *   metadata.json` rows (`identifier`/`version.range`/
 *   `version.artifactVersion`/`path`/`isObfuscated` — the
 *   `net.neoforged.jarjar.metadata` schema FML's deserializer
 *   requires); outer entries are STORED — forge's runtime opens
 *   embedded jars by byte range and cannot range-read a deflated
 *   entry. `Input.fmlModType` stamps the nested jar's manifest with
 *   `FMLModType` (manifest emitted as the first entry) so FML
 *   classifies mod-file type/layer without `mods.toml`.
 *
 * [recompress] (default on): the nested jar is rewritten before
 * embedding — entries sorted by name, stamped [ZIP_EPOCH], STORED for
 * `fabric` so the outer DEFLATE compresses the whole stream once
 * (verbatim double-deflate gains nothing), DEFLATED-fresh for `jarjar`
 * where STORED-outer is already required. Off = byte-verbatim copy.
 * Deterministic either way: fixed timestamps, stable entry order.
 */
object NestedJarsAppender {

    /** Fixed DOS epoch stamped on rewritten/synthesized entries —
     *  reproducible builds (1.x `CONSTANT_TIME_FOR_ZIP_ENTRIES`). */
    const val ZIP_EPOCH: Long = 318038400000L // 1980-02-01T00:00:00Z

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class Input(
        /** Resolved jar file to embed. */
        val path: Path,
        /** Coordinates for metadata/file naming — absent on file deps. */
        val coords: MavenCoords?,
        /** Declared dependency version constraint for jarjar's
         *  `version.range`; resolved version is used when absent. */
        val mavenVersionRange: String? = null,
        /** `FMLModType` manifest attribute stamped into the nested jar
         *  (jarjar format only) — declares FML's mod-file type/layer
         *  without `mods.toml` inside the jar (`MOD`/`LIBRARY`/
         *  `GAMELIBRARY`/`LANGPROVIDER`/`CUSTOM`). Forces the inner
         *  rewrite; unset embeds as-is (FML defaults `GAMELIBRARY`). */
        val fmlModType: String? = null,
        /** jarjar `isObfuscated` metadata override — `null` detects
         *  from the nested jar's `Obfuscated-By` manifest attribute. */
        val obfuscated: Boolean? = null
    )

    /**
     * Append [jars] to [output] under [format]. The output jar is
     * rewritten to a sibling temp and moved atomically.
     *
     * @param metadataFile loader metadata entry name (`fabric.mod.json`
     *   / `quilt.mod.json`); required for `fabric`.
     * @param nestedJarsKey dotted key path for the `{file}` array
     *   (`jars` / `quilt_loader.jars`); required for `fabric`.
     * @param expectedNamespace when set, warn on nested jars stamped
     *   `Unimined-Mapping-Namespace` with a different namespace — the
     *   dev-namespace embedding footgun.
     * @return warnings for the caller to log (namespace mismatches,
     *   dropped duplicate file names).
     */
    fun append(
        output: Path,
        jars: List<Input>,
        format: String,
        recompress: Boolean,
        metadataFile: String?,
        nestedJarsKey: String?,
        expectedNamespace: String? = null
    ): List<String> {
        if (jars.isEmpty()) return emptyList()
        require(format == "fabric" || format == "jarjar") {
            "nestedJars format '$format' is not one of: fabric, jarjar"
        }
        if (format == "fabric") {
            require(metadataFile != null && nestedJarsKey != null) {
                "nestedJars format 'fabric' requires metadataFile and " +
                        "nestedJarsKey (e.g. fabric.mod.json + jars)"
            }
        }
        val warnings = mutableListOf<String>()
        val dir = if (format == "jarjar") "META-INF/jarjar" else "META-INF/jars"

        // resolve entries: name dedup + inner bytes (recompressed or
        // verbatim) + per-format metadata contributions
        val seen = mutableSetOf<String>()
        val packed = jars.mapNotNull { input ->
            val coords = input.coords
                ?: if (format == "jarjar") synthesizeCoords(input.path) else null
            val fileName = coords?.fileName ?: input.path.name
            // 1.x parity: non-jar artifacts don't belong in META-INF/jars
            if (coords != null && coords.extension != "jar") {
                warnings += "nested jar '$fileName' skipped — not a " +
                        ".jar artifact"
                return@mapNotNull null
            }
            if (!seen.add(fileName)) {
                warnings += "nested jar '$fileName' skipped — another " +
                        "nested jar already packs that name"
                return@mapNotNull null
            }
            if (expectedNamespace != null) {
                namespaceOf(input.path)?.let { actual ->
                    if (actual != expectedNamespace) warnings +=
                        "nested jar '$fileName' is stamped " +
                        "'$actual' but the target namespace is " +
                        "'$expectedNamespace' — embed the " +
                        "production-namespace artifact instead"
                }
            }
            // fabric-family loaders ignore nested jars without their
            // own metadata file — inject the synthesized stub (1.x
            // includeCache parity); the inject forces an inner rewrite
            // even in verbatim mode — as does an FMLModType stamp
            val needsStub = format == "fabric" &&
                    !hasEntry(input.path, metadataFile!!)
            val stubBytes = if (needsStub)
                synthesizeMetadata(coords, input.path, metadataFile!!)
            else null
            val manifestAttrs = if (format == "jarjar" &&
                input.fmlModType != null)
                mapOf("FMLModType" to input.fmlModType)
            else null
            val recompressed = recompress || needsStub ||
                    manifestAttrs != null
            val bytes = when {
                !recompressed -> Files.readAllBytes(input.path)
                format == "jarjar" -> rewriteJar(
                    input.path, ZipEntry.DEFLATED,
                    manifestAttrs = manifestAttrs)
                else -> rewriteJar(
                    input.path, ZipEntry.STORED,
                    extra = stubBytes?.let { mapOf(metadataFile!! to it) }
                )
            }
            val obfuscated = input.obfuscated
                ?: (manifestAttrOf(input.path, "Obfuscated-By") != null)
            // outer entry: DEFLATE only over freshly-STORED inner bytes
            // (single compression pass); jarjar stays STORED for the
            // runtime's byte-range reads; verbatim stays STORED —
            // deflating compressed bytes twice buys nothing
            val outerMethod = when {
                format == "jarjar" -> ZipEntry.STORED
                recompressed -> ZipEntry.DEFLATED
                else -> ZipEntry.STORED
            }
            PackedJar(fileName, bytes, input, coords, outerMethod,
                obfuscated)
        }
        if (packed.isEmpty()) return warnings

        val tmp = output.tempSibling()
        try {
            rewriteOuter(output, tmp) { writer ->
                val existing = writer.existing
                when (format) {
                    "jarjar" -> {
                        writer.addOrReplace(
                            "$dir/metadata.json",
                            jarjarMetadata(existing[metadataPath(dir)], packed)
                                .toByteArray()
                        )
                    }
                    else -> {
                        require(metadataFile != null && nestedJarsKey != null)
                        val meta = existing[metadataFile]
                            ?: throw IllegalStateException(
                                "$metadataFile not found in jar — cannot " +
                                "declare nested jars"
                            )
                        writer.addOrReplace(
                            metadataFile,
                            fabricMetadata(meta, nestedJarsKey, packed, dir)
                                .toByteArray()
                        )
                    }
                }
                for (p in packed) {
                    val entry = ZipEntry("$dir/${p.fileName}").apply {
                        time = ZIP_EPOCH
                        if (p.outerMethod == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = p.bytes.size.toLong()
                            compressedSize = p.bytes.size.toLong()
                            crc = CRC32().also { it.update(p.bytes) }.value
                        }
                    }
                    writer.putNextEntry(entry, p.bytes)
                }
            }
            tmp.moveAtomicTo(output)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return warnings
    }

    private fun metadataPath(dir: String) = "$dir/metadata.json"

    private class PackedJar(
        val fileName: String,
        val bytes: ByteArray,
        val input: Input,
        val coords: MavenCoords?,
        val outerMethod: Int,
        val obfuscated: Boolean
    )

    private fun manifestAttrOf(path: Path, attr: String): String? =
        runCatching {
            JarFile(path.toFile()).use { jar ->
                jar.manifest?.mainAttributes?.getValue(attr)
            }
        }.getOrNull()

    private fun namespaceOf(path: Path): String? =
        manifestAttrOf(path, JarLint.NAMESPACE_ATTRIBUTE)

    /** jarjar needs `identifier`/`jarVersion` rows for every embedded
     *  jar — local file deps have no Maven coords, so synthesize from
     *  the manifest's `Automatic-Module-Name` + `Implementation-Version`
     *  (the module identity plan §784 specifies). `null` when the jar
     *  declares neither. */
    private fun synthesizeCoords(path: Path): MavenCoords? {
        val manifest = runCatching {
            JarFile(path.toFile()).use { it.manifest }
        }.getOrNull() ?: return null
        val attrs = manifest.mainAttributes
        val artifact = attrs.getValue("Automatic-Module-Name") ?: return null
        val version = attrs.getValue("Implementation-Version") ?: "1.0.0"
        return MavenCoords("embedded", artifact, version)
    }

    private fun hasEntry(path: Path, name: String): Boolean =
        ZipFile(path.toFile()).use { it.getEntry(name) != null }

    private fun String.sha256(start: Int, length: Int): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
            .substring(start, start + length)
    }

    /** A nested jar carrying no loader metadata gets a synthesized
     *  stub so the loader can accept it (1.x parity: the id is
     *  group_artifact[_classifier], ≤64 chars, `.`→`_`; file deps fall
     *  back to the file name). Emits `fabric.mod.json` or
     *  `quilt.mod.json` shape per [metadataFile]. */
    private fun synthesizeMetadata(
        coords: MavenCoords?,
        path: Path,
        metadataFile: String
    ): ByteArray {
        val artifact = coords?.artifact ?: path.name.substringBeforeLast('.')
        var artifactString = buildString {
            coords?.group?.let { append(it).append('_') }
            append(artifact)
            coords?.classifier?.let { append('_').append(it) }
        }
        if (artifactString.length > 64) {
            artifactString = artifactString.substring(0, 50) +
                    artifactString.sha256(0, 14)
        }
        val id = artifactString.replace(".", "_").lowercase()
        val version = coords?.version ?: "1.0.0"
        return if (metadataFile == "quilt.mod.json") buildJsonObject {
            put("schema_version", 1)
            putJsonObject("quilt_loader") {
                put("group", coords?.group ?: "unimined.generated")
                put("id", id)
                put("version", version)
                putJsonObject("metadata") { put("name", artifact) }
            }
            putJsonObject("custom") { put("unimined:generated", true) }
        }.toString().toByteArray() else buildJsonObject {
            put("schemaVersion", 1)
            put("id", id)
            put("version", version)
            put("name", artifact)
            putJsonObject("custom") {
                put("fabric-loom:generated", true)
                put("unimined:generated", true)
            }
        }.toString().toByteArray()
    }

    private fun rewriteJar(
        input: Path,
        method: Int,
        extra: Map<String, ByteArray>? = null,
        manifestAttrs: Map<String, String>? = null
    ): ByteArray {
        data class E(val name: String, val bytes: ByteArray)
        val entries = ZipFile(input.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }
                .map { e -> E(e.name, zip.getInputStream(e).readBytes()) }
                .sortedBy { it.name }.toList()
        }
        // stamped manifests emit as the first entry — JarFile only
        // recognizes META-INF/MANIFEST.MF in the leading entries,
        // and a name-sorted copy usually lands it mid-archive
        val manifestBytes = manifestAttrs?.let { attrs ->
            val mf = entries.firstOrNull {
                it.name.equals("META-INF/MANIFEST.MF", true)
            }?.let { e ->
                java.io.ByteArrayInputStream(e.bytes)
                    .use { Manifest(it) }
            } ?: Manifest().apply {
                mainAttributes[
                    java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            }
            attrs.forEach { (k, v) -> mf.mainAttributes.putValue(k, v) }
            java.io.ByteArrayOutputStream().also { mf.write(it) }
                .toByteArray()
        }
        val out = java.io.ByteArrayOutputStream()
        JarOutputStream(out).use { jout ->
            fun writeEntry(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name).apply {
                    time = ZIP_EPOCH
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().also { it.update(bytes) }.value
                    }
                }
                jout.putNextEntry(entry)
                jout.write(bytes)
                jout.closeEntry()
            }
            if (manifestBytes != null) {
                writeEntry("META-INF/MANIFEST.MF", manifestBytes)
            }
            for (e in entries) {
                if (manifestBytes != null &&
                    e.name.equals("META-INF/MANIFEST.MF", true)) continue
                writeEntry(e.name, e.bytes)
            }
            extra?.forEach { (name, bytes) -> writeEntry(name, bytes) }
        }
        return out.toByteArray()
    }

    /** Whole-jar rewrite: entries pass through verbatim (original
     *  method + timestamp), the collector updates metadata and appends
     *  the nested-jar entries. */
    private fun rewriteOuter(
        input: Path,
        output: Path,
        collect: (OuterWriter) -> Unit
    ) {
        val existing = LinkedHashMap<String, ByteArray>()
        val order = mutableListOf<String>()
        val times = HashMap<String, Long>()
        val methods = HashMap<String, Int>()
        ZipFile(input.toFile()).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val name = e.name
                existing[name] = zip.getInputStream(e).readBytes()
                order += name
                times[name] = e.time
                methods[name] = e.method
            }
        }
        val writer = OuterWriter(existing)
        collect(writer)

        JarOutputStream(Files.newOutputStream(output)).use { jout ->
            val written = HashSet<String>()
            for (name in order) {
                val bytes = existing[name] ?: continue // dropped
                val entry = ZipEntry(name).apply {
                    time = times[name] ?: ZIP_EPOCH
                    if (methods[name] == ZipEntry.STORED) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().also { it.update(bytes) }.value
                    }
                }
                jout.putNextEntry(entry)
                jout.write(bytes)
                jout.closeEntry()
                written += name
            }
            for ((name, bytes) in writer.appended) {
                if (!written.add(name)) continue
                val entry = ZipEntry(name).apply { time = ZIP_EPOCH }
                jout.putNextEntry(entry)
                jout.write(bytes)
                jout.closeEntry()
            }
            for ((name, bytes, entry) in writer.packedAppends) {
                if (!written.add(name)) continue
                jout.putNextEntry(entry)
                jout.write(bytes)
                jout.closeEntry()
            }
        }
    }

    private class OuterWriter(val existing: LinkedHashMap<String, ByteArray>) {
        /** name → bytes, entries replacing/inserted at end. */
        val appended = LinkedHashMap<String, ByteArray>()
        val packedAppends =
            mutableListOf<Triple<String, ByteArray, ZipEntry>>()

        /** Update an existing entry in place (position preserved) or
         *  append when absent. */
        fun addOrReplace(name: String, bytes: ByteArray) {
            if (existing.containsKey(name)) existing[name] = bytes
            else appended[name] = bytes
        }

        fun putNextEntry(entry: ZipEntry, bytes: ByteArray) {
            packedAppends += Triple(entry.name, bytes, entry)
        }
    }

    private fun jarjarMetadata(existing: ByteArray?, packed: List<PackedJar>): String {
        val jars = mutableListOf<JsonObject>()
        if (existing != null) {
            runCatching {
                (json.parseToJsonElement(existing.decodeToString())
                        as? JsonObject)?.get("jars")
            }.getOrNull()?.let { arr ->
                (arr as? JsonArray)?.filterIsInstance<JsonObject>()
                    ?.let { jars += it }
            }
        }
        val seenIds = jars.mapNotNull {
            (it["identifier"] as? JsonObject)?.let { id ->
                "${id["group"]}|${id["artifact"]}"
            }
        }.toMutableSet()
        for (p in packed) {
            val coords = p.coords
                ?: throw IllegalStateException(
                    "nested jar '${p.input.path}' has no resolvable " +
                    "coordinates — jarjar metadata requires " +
                    "group:artifact:version (module dependencies, or a " +
                    "jar with Automatic-Module-Name/Implementation-Version)"
                )
            val group = coords.group ?: coords.artifact
            if (!seenIds.add("$group|${coords.artifact}")) continue
            jars += buildJsonObject {
                putJsonObject("identifier") {
                    put("group", group)
                    put("artifact", coords.artifact)
                }
                putJsonObject("version") {
                    put("range",
                        p.input.mavenVersionRange
                            ?: "[${coords.version},)")
                    put("artifactVersion", coords.version ?: "1.0.0")
                }
                // required by FML's ContainedJarMetadata deserializer —
                // the nested jar's location inside the outer archive
                put("path", "META-INF/jarjar/${p.fileName}")
                put("isObfuscated", p.obfuscated)
            }
        }
        return buildJsonObject { putJsonArray("jars") { jars.forEach { add(it) } } }
            .toString()
    }

    /** `obj[a.b.c]` navigation — null when the path or a non-object
     *  node is missing. */
    private fun getAtPath(obj: JsonObject, path: List<String>): JsonObject? {
        var node = obj
        for (key in path.dropLast(1)) {
            node = node[key] as? JsonObject ?: return null
        }
        return node
    }

    /** Immutable `obj[a.b.c] = v` — intermediate objects are created. */
    private fun putAtPath(
        obj: JsonObject,
        path: List<String>,
        value: kotlinx.serialization.json.JsonElement
    ): JsonObject {
        val key = path.first()
        val child = if (path.size == 1) value
        else putAtPath(
            (obj[key] as? JsonObject) ?: JsonObject(emptyMap()),
            path.drop(1), value
        )
        return JsonObject(obj + (key to child))
    }

    /** Merge `{file}` rows into the metadata key — existing rows win
     *  (first-writer-wins like the jar copy spec's EXCLUDE strategy),
     *  then the new nested jars append in declaration order. */
    private fun fabricMetadata(
        existing: ByteArray,
        nestedJarsKey: String,
        packed: List<PackedJar>,
        dir: String
    ): String {
        val root = json.parseToJsonElement(existing.decodeToString()) as JsonObject
        val keys = nestedJarsKey.split('.')

        val parent = getAtPath(root, keys) ?: root
        val arrayKey = keys.last()
        val current = (parent[arrayKey] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?: emptyList()
        val seen = current.mapNotNull {
            (it["file"] as? JsonPrimitive)?.content
        }.toMutableSet()
        val merged = buildJsonArray {
            current.forEach { add(it) }
            for (p in packed) {
                val path = "$dir/${p.fileName}"
                if (seen.add(path)) {
                    add(buildJsonObject { put("file", path) })
                }
            }
        }
        return putAtPath(root, keys, merged).toString()
    }

}
