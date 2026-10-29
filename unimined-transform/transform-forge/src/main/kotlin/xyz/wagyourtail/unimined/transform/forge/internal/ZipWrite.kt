package xyz.wagyourtail.unimined.transform.forge.internal

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes [name] to the stream, synthesizing any parent `dir/` entries
 * not already in [emittedDirs] first.
 *
 * Explicit directory entries are required for package resource
 * enumeration — `ClassLoader.getResources("com/foo")` only sees a
 * package if the `com/foo/` entry exists. log4j2's `packages=` plugin
 * scan and FML's directory discovery both fail silently against jars
 * that omit them.
 *
 * @param name entry path (`/` separated)
 * @param emittedDirs tracks already-written dir entries; mutated as
 *        parents are emitted — share one set across a whole jar write
 * @param time entry timestamp; `0L` for deterministic output
 * @param write body writer, invoked with the open stream
 */
internal fun ZipOutputStream.putEntryWithDirs(
    name: String,
    emittedDirs: MutableSet<String>,
    time: Long = 0L,
    write: (OutputStream) -> Unit
) {
    var i = name.indexOf('/')
    while (i != -1) {
        val dir = name.substring(0, i + 1)
        if (emittedDirs.add(dir)) {
            putNextEntry(ZipEntry(dir).apply { this.time = time })
            closeEntry()
        }
        i = name.indexOf('/', i + 1)
    }
    // explicit `dir/` input entry — the parent loop already emitted
    // it; writing it again would be a duplicate
    if (name.endsWith('/')) return
    putNextEntry(ZipEntry(name).apply { this.time = time })
    write(this)
    closeEntry()
}
