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

    /** Maven coordinates declared by [distJar]'s `libraries.list`. */
    fun coords(distJar: Path): List<String> {
        ZipFile(distJar.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/libraries.list") ?: return emptyList()
            return zip.getInputStream(entry).bufferedReader().readLines()
                .mapNotNull { line ->
                    line.split(Regex("\\s+")).firstOrNull { MAVEN_COORD.matches(it) }
                }
        }
    }
}
