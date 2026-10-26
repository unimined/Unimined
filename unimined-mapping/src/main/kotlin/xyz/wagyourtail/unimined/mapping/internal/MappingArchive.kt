package xyz.wagyourtail.unimined.mapping.internal

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipException
import org.gradle.api.GradleException

/**
 * Unwraps a mapping document embedded in a jar/zip artifact — the
 * `net.fabricmc:yarn`/`intermediary` convention of shipping
 * `mappings/mappings.tiny` inside the published jar. `mappings/mappings.tiny`
 * is always preferred; otherwise a single recognized mapping entry is
 * required — ambiguity fails loudly rather than picking wrong.
 */
object MappingArchive {

    private val MAPPING_ENTRY =
        Regex(""".*\.(tiny|tsrg|srg|csrg|xsrg|pro|mappings?|map)(\.gz)?$""")

    /**
     * The mapping files inside [archive], or null when it isn't a zip or
     * holds no mapping entries. [cacheDir] receives the extracted file,
     * named deterministically so re-materialization reuses it.
     */
    fun extract(archive: Path, cacheDir: Path): List<Path>? {
        if (!archive.fileName.toString().let { it.endsWith(".jar") || it.endsWith(".zip") }) {
            return null
        }
        return try {
            JarFile(archive.toFile()).use { jar ->
                val preferred = jar.getJarEntry("mappings/mappings.tiny")
                val candidates = preferred?.let(::listOf)
                    ?: jar.entries().asSequence()
                        .filter { !it.isDirectory && MAPPING_ENTRY.matches(it.name) }
                        .toList()
                when {
                    candidates.isEmpty() -> null
                    candidates.size > 1 -> throw GradleException(
                        "mapping archive $archive contains multiple candidate " +
                            "documents (${candidates.joinToString { it.name }}); " +
                            "declare the inner file via `file()` instead"
                    )
                    else -> listOf(extractEntry(jar, archive, candidates.single(), cacheDir))
                }
            }
        } catch (e: ZipException) {
            null
        }
    }

    private fun extractEntry(
        jar: JarFile,
        archive: Path,
        entry: java.util.jar.JarEntry,
        cacheDir: Path
    ): Path {
        val out = cacheDir.resolve(
            "${archive.fileName}-${entry.name.replace('/', '_')}"
        )
        if (!Files.exists(out)) {
            Files.createDirectories(cacheDir)
            jar.getInputStream(entry).use { Files.copy(it, out) }
        }
        return out
    }

}
