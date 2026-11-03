package xyz.wagyourtail.unimined.minecraft.merge

import xyz.wagyourtail.commonskt.jvm.moveAtomicTo
import xyz.wagyourtail.commonskt.jvm.tempSibling
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bundled-library membership strip — drop jar entries that exist
 * inside a resolved library jar. The rule [JarMerger] applies to
 * bundled server libraries during merge, shared here as a standalone
 * jar→jar operation (e.g. stripping a conversion dist's shaded libs
 * pre-merge, or a sided artifact whose copies are declared as real
 * dependencies and ride the classpath instead).
 */
object BundledLibraryStrip {

    /**
     * Rewrite [input] to [out] without entries that exist inside
     * [libraryJars]. Directory entries are re-synthesized from the
     * survivors (legacy `getResource("dir/")` enumeration needs
     * them) — emptied directories are dropped with their contents.
     */
    fun strip(input: Path, libraryJars: List<Path>, out: Path) {
        val names = entryNames(libraryJars)
        val tmp = out.tempSibling()
        try {
            ZipInputStream(input.inputStream()).use { zin ->
                val entries = mutableListOf<Pair<String, ByteArray>>()
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name !in names) {
                        entries.add(e.name to zin.readBytes())
                    }
                    e = zin.nextEntry
                }
                val dirs = java.util.TreeSet<String>()
                for ((name, _) in entries) {
                    var i = name.indexOf('/')
                    while (i > 0) {
                        dirs.add(name.substring(0, i + 1))
                        i = name.indexOf('/', i + 1)
                    }
                }
                ZipOutputStream(tmp.outputStream()).use { zout ->
                    for (d in dirs) {
                        zout.putNextEntry(java.util.zip.ZipEntry(d))
                        zout.closeEntry()
                    }
                    for ((name, bytes) in entries) {
                        zout.putNextEntry(java.util.zip.ZipEntry(name))
                        zout.write(bytes)
                        zout.closeEntry()
                    }
                }
            }
            tmp.moveAtomicTo(out)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Union of entry names across [jars] — central-directory read
     *  only. `META-INF/MANIFEST.MF` and `META-INF/services/`
     *  entries are excluded: the manifest exists in every jar
     *  (matching it would strip the input's own manifest), and
     *  service files are
     *  registrations, not library code — a service file in the input
     *  may name providers that only exist there, and a registration
     *  that *does* point at a bundled provider still resolves because
     *  the real library jar carries the class on the classpath. */
    fun entryNames(jars: List<Path>): Set<String> {
        val names = HashSet<String>()
        for (jar in jars) {
            ZipFile(jar.toFile()).use { zf ->
                zf.entries().asSequence()
                    .filterNot {
                        it.name.equals("META-INF/MANIFEST.MF",
                                ignoreCase = true) ||
                                it.name.startsWith("META-INF/services/",
                                        ignoreCase = true)
                    }
                    .forEach { names.add(it.name) }
            }
        }
        return names
    }
}
