package xyz.wagyourtail.unimined.provider.devbundle

import kotlinx.serialization.json.Json
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.util.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Gradle-free dev-bundle writer — the export counterpart to
 * [DevBundleProduction]'s reader pipeline, same object/adapter split
 * (`DevBundleExport` does the file work; `ExportDevBundleTask` feeds it
 * Gradle properties).
 *
 * The emitted zip is a paperweight dev bundle (`data-version.txt` +
 * `config.json` + `data/` + `patches/`) consumable by
 * [DevBundleProvider]/[DevBundleArtifactProducer] — and, modulo the
 * unimined-specific paperclip stand-in, by any reader honouring the
 * bundler/paperclip contract. Because unimined exports the
 * *already-built* prod jar rather than vanilla + binary patches, the
 * bundle's `data/paperclip-mojang.jar` is a self-contained reproducer:
 * `META-INF/versions.list` names an embedded `META-INF/versions/`
 * entry carrying the prod jar, and the `Main-Class` is
 * [BundleBootstrap][BOOTSTRAP_CLASS] — a generated jar whose only job
 * is extracting that entry where a real paperclip would produce it
 * (`versions/`, plus the legacy `cache/patched*` mirror). Bundles are
 * therefore larger than patch-based ones but exact: no patching can
 * ever diverge from what the exporting env produced.
 */
internal object DevBundleExport {

    /** `data-version.txt` we emit — the first flat (post-`buildData`)
     *  layout; every field we write lives at top level there and the
     *  reader accepts the whole 2–8 range. */
    const val DATA_VERSION = 6

    /** In-zip paperclip path — paperweight's own entry name. */
    const val PAPERCLIP_PATH = "data/paperclip-mojang.jar"

    /** In-zip reobf-mappings path — paperweight's own entry name. */
    const val REOBF_PATH = "data/mojang-spigot-reobf.tiny"

    /** In-zip source-patch directory. */
    const val PATCH_DIR = "patches"

    /** The generated reproducer class written into the paperclip jar —
     *  `src/bootstrap/java` compiled at `--release 8` so bundles run on
     *  any era's JVM floor; loaded as a classpath resource here. */
    const val BOOTSTRAP_CLASS =
        "xyz.wagyourtail.unimined.provider.devbundle.bootstrap.BundleBootstrap"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
    }

    /** A library row for `META-INF/libraries.list` — [file] non-null
     *  means the jar is physically embedded in the paperclip
     *  (`META-INF/libraries/<name>`), so real bundler consumers can
     *  extract it; coord-only rows carry `-` hashes and resolve from
     *  the declared repositories instead. */
    data class Library(val coordinate: String, val file: Path? = null)

    /** Everything an export needs — plain values, no Gradle. */
    data class Spec(
        /** The bundle's `minecraftVersion` — the MC the prod jar patches. */
        val minecraftVersion: String,
        /** The bundler `version.json`/`versions.list` id — also the
         *  embedded jar's file stem. */
        val bundleId: String,
        /** The fully-built production artifact the bundle reproduces. */
        val serverJar: Path,
        /** Classpath libs — coord-only rows plus embedded jars. */
        val libraries: List<Library>,
        /** Maven repos the coords resolve from — mirrored verbatim. */
        val libraryRepositories: List<String>,
        /** Fork api coordinate for the compile classpath (optional —
         *  a `buildData`-era field our reader still honors). */
        val apiCoordinates: String? = null,
        /** Mojang-mapped api coordinate (optional, same caveat). */
        val mojangApiCoordinates: String? = null,
        /** mojmap→prod reobf mappings to ship at [REOBF_PATH]. */
        val reobfMappingsFile: Path? = null,
        /** Source-patch directory copied to [PATCH_DIR] as-is. */
        val sourcePatches: Path? = null,
        /** The launched server's main class (`META-INF/main-class`). */
        val mainClass: String = "net.minecraft.server.Main",
    )

    /** Write the bundle zip to [outputZip] (atomically) and return it. */
    fun export(spec: Spec, outputZip: Path): Path {
        require(spec.serverJar.exists()) {
            "devbundle export: serverJar not found: ${spec.serverJar}"
        }
        val paperclip = Files.createTempFile("unimined-devbundle", ".jar")
        try {
            buildPaperclipJar(spec, paperclip)
            outputZip.parent?.let { Files.createDirectories(it) }
            outputZip.writeAtomically { tmp ->
                ZipOutputStream(
                    Files.newOutputStream(tmp).buffered()
                ).use { zip ->
                    entry(zip, "data-version.txt") {
                        DATA_VERSION.toString().toByteArray()
                    }
                    entry(zip, "config.json") { configJson(spec).toByteArray() }
                    entry(zip, PAPERCLIP_PATH) { Files.readAllBytes(paperclip) }
                    spec.reobfMappingsFile?.let { file ->
                        require(file.exists()) {
                            "devbundle export: reobfMappingsFile not found: $file"
                        }
                        entry(zip, REOBF_PATH) { Files.readAllBytes(file) }
                    }
                    spec.sourcePatches?.let { dir ->
                        require(dir.isDirectory()) {
                            "devbundle export: sourcePatches is not a " +
                                    "directory: $dir"
                        }
                        // sorted walk → deterministic entry order
                        Files.walk(dir).use { walk ->
                            walk.filter { Files.isRegularFile(it) }
                                .sorted().forEach { file ->
                                    val rel = dir.relativize(file)
                                        .joinToString("/") { it.name }
                                    entry(zip, "$PATCH_DIR/$rel") {
                                        Files.readAllBytes(file)
                                    }
                                }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(paperclip)
        }
        return outputZip
    }

    /** The bundle's `config.json` — a data-version-[DATA_VERSION]
     *  (flat) [DevBundleConfig] serialization. */
    fun configJson(spec: Spec): String = json.encodeToString(
        DevBundleConfig.serializer(),
        DevBundleConfig(
            minecraftVersion = spec.minecraftVersion,
            apiCoordinates = spec.apiCoordinates,
            mojangApiCoordinates = spec.mojangApiCoordinates,
            mojangMappedPaperclipFile = PAPERCLIP_PATH,
            reobfMappingsFile = spec.reobfMappingsFile?.let { REOBF_PATH },
            patchDir = spec.sourcePatches?.let { PATCH_DIR },
            libraryRepositories = spec.libraryRepositories,
        )
    )

    /**
     * Pair declared coordinates with embeddable jar files by the maven
     * filename convention (`<artifact>-<version>[-classifier].<ext>`):
     * every coord yields a row (embedded when matched), and files no
     * coord claims join as filename-coord rows at the end.
     */
    fun pairLibraries(
        coordinates: List<String>,
        files: List<Path>
    ): List<Library> {
        val claimed = mutableSetOf<Path>()
        val libs = coordinates.map { coord ->
            val match = files.firstOrNull { file ->
                coordFilePrefix(coord)?.let { file.matchesPrefix(it) }
                    ?: false
            }
            if (match != null) claimed.add(match)
            Library(coord, match)
        }
        return libs + files.filter { it !in claimed }
            .map { Library(it.name.substringBeforeLast('.'), it) }
    }

    private fun Path.matchesPrefix(prefix: String): Boolean =
        name.startsWith("$prefix.") || name.startsWith("$prefix-")

    /** `<artifact>-<version>` prefix for `g:a:v` (4-part coords keep
     *  their classifier in the prefix). */
    fun coordFilePrefix(coordinate: String): String? {
        val parts = coordinate.removeSuffix("@jar").split(":")
        return when {
            parts.size >= 3 -> "${parts[1]}-${parts[2]}" +
                    (parts.getOrNull(3)?.let { "-$it" } ?: "")
            else -> null
        }
    }

    /** Build the paperclip jar at [out] — the self-contained
     *  reproducer embedding the prod jar + bootstrap + bundler lists. */
    fun buildPaperclipJar(spec: Spec, out: Path) {
        val versionName = sanitize(spec.bundleId)
        val serverSha = sha1(spec.serverJar)
        JarOutputStream(
            Files.newOutputStream(out).buffered(),
            Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                mainAttributes.putValue("Main-Class", BOOTSTRAP_CLASS)
            }
        ).use { jar ->
            fun entry(name: String, data: ByteArray) {
                val e = JarEntry(name)
                e.time = 0 // deterministic output
                jar.putNextEntry(e)
                jar.write(data)
                jar.closeEntry()
            }
            entry("version.json", """{"id": "$versionName"}""".toByteArray())
            entry("META-INF/main-class", spec.mainClass.toByteArray())
            entry(
                "META-INF/versions.list",
                "$serverSha\t$versionName\t$versionName.jar\n".toByteArray()
            )
            entry(
                "META-INF/versions/$versionName.jar",
                Files.readAllBytes(spec.serverJar)
            )
            val embedded = spec.libraries.filter { it.file != null }
            val rows = spec.libraries.joinToString("") { lib ->
                val file = lib.file
                if (file != null) {
                    require(file.exists()) {
                        "devbundle export: embedded library not found: $file"
                    }
                    "${sha1(file)}\t${lib.coordinate}\t${file.name}\n"
                } else {
                    // coord-only row — the consumer resolves it from
                    // the declared repositories; the path column names
                    // the conventional artifact filename
                    "-\t${lib.coordinate}\t${conventionalName(lib.coordinate)}\n"
                }
            }
            entry("META-INF/libraries.list", rows.toByteArray())
            // dedupe by entry name — two coords may resolve the same
            // file, or names may collide across artifacts
            val seen = mutableSetOf<String>()
            for (lib in embedded) {
                val file = lib.file!!
                if (seen.add(file.name)) {
                    entry(
                        "META-INF/libraries/${file.name}",
                        Files.readAllBytes(file)
                    )
                }
            }
            entry(
                BOOTSTRAP_CLASS.replace('.', '/') + ".class",
                bootstrapBytes()
            )
        }
    }

    /** The bootstrap class bytes — compiled into this artifact at
     *  `--release 8` by the module's `compileBundleBootstrap` task. */
    fun bootstrapBytes(): ByteArray =
        DevBundleExport::class.java.classLoader.getResourceAsStream(
            BOOTSTRAP_CLASS.replace('.', '/') + ".class"
        )?.readBytes() ?: throw IllegalStateException(
            "devbundle bootstrap class $BOOTSTRAP_CLASS is missing from " +
                    "the classpath — the provider-devbundle jar is broken"
        )

    private fun conventionalName(coordinate: String): String =
        coordFilePrefix(coordinate)?.let { "$it.jar" }
            ?: coordinate.substringAfterLast(':') + ".jar"

    private fun sanitize(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun entry(
        zip: ZipOutputStream, name: String, data: () -> ByteArray
    ) {
        val e = ZipEntry(name)
        e.time = 0 // deterministic output
        zip.putNextEntry(e)
        zip.write(data())
        zip.closeEntry()
    }

    private fun sha1(file: Path): String =
        MessageDigest.getInstance("SHA-1")
            .digest(Files.readAllBytes(file)).toHex()

}
