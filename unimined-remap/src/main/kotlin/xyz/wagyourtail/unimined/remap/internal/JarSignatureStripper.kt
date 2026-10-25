package xyz.wagyourtail.unimined.remap.internal

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream

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

    /** Strip signature entries from [path] if it is a jar; no-op otherwise. */
    fun sanitize(path: Path) {
        if (!path.isRegularFile() || !path.name.endsWith(".jar", ignoreCase = true)) return
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
        manifest.entries.forEach { (name, attrs) ->
            val pruned = Attributes(attrs)
            pruned.keys.toList()
                .filter { it.toString().contains("Digest", ignoreCase = true) }
                .forEach { pruned.remove(it) }
            if (pruned.isNotEmpty()) {
                stripped.entries[name] = pruned
            }
        }
        manifestPath.outputStream().use { stripped.write(it) }
    }

}
