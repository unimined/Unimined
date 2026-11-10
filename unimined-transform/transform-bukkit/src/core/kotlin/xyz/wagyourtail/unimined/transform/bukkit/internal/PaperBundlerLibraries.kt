package xyz.wagyourtail.unimined.transform.bukkit.internal

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reads the paperclip bundler's `META-INF/libraries.list` — the
 * runtime classpath the patched server needs (paper >=1.20.4 dist
 * jars). Each line is tab/space-separated fields; paperclip emits
 * `<sha256>\t<maven-coords>\t<name>` so the coord is the field shaped
 * `g:a:v` — field-order defensive against layout drift.
 *
 * Legacy dist jars (pre-bundler) carry no such entry — `coords`
 * returns empty and the server runs off the vanilla library set.
 */
internal object PaperBundlerLibraries {

    private val MAVEN_COORD = Regex("[^\\s:]+:[^\\s:]+:[^\\s:]+")

    /** One `libraries.list` row — [coord] always resolves; [embeddedPath]
     *  is set when the dist jar bundles the jar itself (paperclip
     *  extracts rather than downloads — snapshot coords like
     *  paper-mojangapi are not on public mavens). */
    class Entry(val coord: String, val embeddedPath: String?)

    /** `libraries.list` rows of [distJar], in declaration order. */
    fun entries(distJar: Path): List<Entry> {
        ZipFile(distJar.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/libraries.list") ?: return emptyList()
            return zip.getInputStream(entry).bufferedReader().readLines()
                .mapNotNull { line ->
                    val fields = line.split(Regex("\\s+"))
                    val coord = fields.firstOrNull { MAVEN_COORD.matches(it) }
                        ?: return@mapNotNull null
                    val path = fields.lastOrNull { it.endsWith(".jar") }
                    Entry(coord, path?.takeIf { zip.getEntry("META-INF/libraries/$it") != null })
                }
        }
    }

    /** Maven coordinates declared by [distJar]'s `libraries.list`. */
    fun coords(distJar: Path): List<String> =
        entries(distJar).map { it.coord }

    /** Extract each embedded library jar into [outDir] (flat, name-keyed)
     *  and return the file path per [Entry] — `null` for non-embedded
     *  rows, which resolve through maven instead. */
    fun extractEmbedded(distJar: Path, outDir: Path): List<Pair<Entry, Path?>> {
        val rows = entries(distJar)
        val embedded = rows.filter { it.embeddedPath != null }
        if (embedded.isEmpty()) return rows.map { it to null }
        ZipFile(distJar.toFile()).use { zip ->
            return rows.map { row ->
                val rel = row.embeddedPath ?: return@map row to null
                val dest = outDir.resolve(rel.substringAfterLast('/'))
                if (!java.nio.file.Files.exists(dest)) {
                    java.nio.file.Files.createDirectories(dest.parent)
                    zip.getInputStream(zip.getEntry("META-INF/libraries/$rel")).use { input ->
                        java.nio.file.Files.copy(input, dest)
                    }
                }
                row to dest
            }
        }
    }
}
