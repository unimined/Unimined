package xyz.wagyourtail.unimined.mapping.internal

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipException

/**
 * Unwraps a mapping document embedded in a jar/zip artifact — the
 * `net.fabricmc:yarn`/`intermediary` convention of shipping
 * `mappings/mappings.tiny` inside the published jar. `mappings/mappings.tiny`
 * is always preferred; otherwise a single recognized mapping entry is
 * required — ambiguity fails loudly rather than picking wrong.
 */
object MappingArchive {

    private val MAPPING_ENTRY =
        Regex(""".*\.(tiny|tsrg|srg|csrg|xsrg|pro|mappings?|map|csv)(\.gz)?$""")

    /**
     * Extracted [archive] contents — the mapping documents plus
     * companion entries (the `extras` directory's attachments, such as
     * `record_signatures.json`), keyed by entry path.
     */
    class Contents(
        val documents: List<Path>,
        val attachments: Map<String, Path>
    )

    /**
     * The mapping files inside [archive], or null when it isn't a zip or
     * holds no mapping entries. [cacheDir] receives the extracted file,
     * named deterministically so re-materialization reuses it.
     */
    fun extract(archive: Path, cacheDir: Path): List<Path>? =
        extractAll(archive, cacheDir)?.documents

    /**
     * As [extract], plus any `extras`-directory companion entries as
     * [Contents.attachments].
     */
    fun extractAll(archive: Path, cacheDir: Path): Contents? {
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
                if (candidates.isEmpty()) return null
                // multi-document archives (e.g. `mcp_stable`'s fields/
                // methods/params csvs) merge into one tree downstream —
                // extract all, sorted for determinism
                val attachments = jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("extras/") }
                    .associate { it.name to extractEntry(jar, archive, it, cacheDir) }
                Contents(
                    candidates.sortedBy { it.name }
                        .map { extractEntry(jar, archive, it, cacheDir) },
                    attachments
                )
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
        // keep entry names verbatim under an archive-named dir — some
        // readers detect on filename (e.g. MCP's `params.csv`). Era zips
        // may carry Windows-style `conf\classes.csv` entries — normalize
        // to real path separators so they land as nested files.
        val out = cacheDir.resolve(archive.fileName.toString())
            .resolve(entry.name.replace('\\', '/'))
        if (!Files.exists(out)) {
            Files.createDirectories(out.parent)
            jar.getInputStream(entry).use { Files.copy(it, out) }
        }
        return out
    }

}
