package xyz.wagyourtail.unimined.remap.internal

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.*

/**
 * Removes JAR signing metadata from a rewritten archive.
 *
 * Steps that modify class bytes invalidate the original `META-INF` `.SF`
 * digests; leaving them in place makes `JarFile` throw
 * `SecurityException: Invalid signature file digest` when any later step
 * (or the runtime classloader) reopens the jar. Signing files are dropped
 * outright and manifest `Name:` sections are pruned of their digest
 * attributes, matching what Loom/ForgeGradle do to remapped outputs.
 */
object JarSignatureStripper {

    private val SIGNATURE_EXTENSIONS = setOf("SF", "RSA", "DSA", "EC")

    /**
     * Cheap metadata-only scan for signing content: signature entries
     * under `META-INF/`, or a manifest carrying `*Digest*` attributes.
     * Lets callers skip the ZipFS reopen+rewrite entirely on unsigned
     * jars — the overwhelmingly common case (remapped MC/mod output is
     * never signed); a `ZipFile` central-dir walk costs ~ms vs a full
     * archive mutation pass.
     */
    fun needsSanitize(path: Path): Boolean {
        if (!path.isRegularFile() || !path.name.endsWith(".jar", ignoreCase = true)) return false
        ZipFile(path.toFile()).use { zip ->
            var manifest: java.util.zip.ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("META-INF/", ignoreCase = true)) {
                    if (name.substringAfterLast('.', "").uppercase() in SIGNATURE_EXTENSIONS) {
                        return true
                    }
                    if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
                        manifest = zip.getEntry(name)
                    }
                }
            }
            if (manifest != null) {
                return zip.getInputStream(manifest).use { Manifest(it) }
                    .entries.values.any { attrs ->
                        attrs.keys.any { it.toString().contains("Digest", ignoreCase = true) }
                    }
            }
        }
        return false
    }

    /** Strip signature entries from [path] if it is a jar; no-op otherwise. */
    fun sanitize(path: Path) {
        if (!needsSanitize(path)) return
        FileSystems.newFileSystem(path).use { fs ->
            val metaInf = fs.getPath("META-INF") ?: return
            if (!metaInf.exists()) return
            Files.list(metaInf).use { entries ->
                entries.filter { entry ->
                    val ext = entry.name.substringAfterLast('.', "")
                    ext.uppercase() in SIGNATURE_EXTENSIONS
                }.forEach { Files.delete(it) }
            }
            val manifestPath = metaInf.resolve("MANIFEST.MF")
            if (manifestPath.exists()) {
                stripDigestSections(manifestPath)
            }
        }
    }

    private fun stripDigestSections(manifestPath: Path) {
        val manifest = manifestPath.inputStream().use { Manifest(it) }
        val stripped = Manifest()
        stripped.mainAttributes.putAll(manifest.mainAttributes)
        var changed = false
        manifest.entries.forEach { (name, attrs) ->
            val pruned = Attributes(attrs)
            pruned.keys.toList()
                .filter { it.toString().contains("Digest", ignoreCase = true) }
                .forEach { pruned.remove(it); changed = true }
            if (pruned.isNotEmpty()) {
                stripped.entries[name] = pruned
            }
        }
        // only rewrite when a digest was actually pruned — otherwise the
        // manifest churns bytes for nothing (and under the needsSanitize
        // guard callers only reach here when something changed anyway)
        if (changed) {
            manifestPath.outputStream().use { stripped.write(it) }
        }
    }

}
