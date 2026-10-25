package xyz.wagyourtail.unimined.provider.native.merge

import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Merges the client + server jars into a combined dev jar for the
 * `MERGED` side. Server entries win on conflict — server code
 * supersedes client stubs (e.g. `minecraft.jar` bundles client classes
 * the server jar also carries).
 */
object JarMerger {

    /** Merge [server] onto [client] → [out]. */
    fun merge(client: Path, server: Path, out: Path) {
        val entries = linkedMapOf<String, ByteArray>()

        fun read(jar: Path) {
            ZipInputStream(jar.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory) entries[e.name] = zin.readBytes()
                    e = zin.nextEntry
                }
            }
        }
        read(client)
        read(server)

        ZipOutputStream(out.outputStream()).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
        }
    }

}
