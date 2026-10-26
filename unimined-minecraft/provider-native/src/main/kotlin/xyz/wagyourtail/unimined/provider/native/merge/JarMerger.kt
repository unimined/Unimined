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
 *
 * `META-INF/` is stripped entirely: besides signatures and manifests
 * (neither valid on merged output), the server jar is a Mojang bundler
 * (`versions.list`/`libraries.list` + a nested `versions/<v>.jar`) —
 * keeping that metadata makes Fabric's `BundlerClassPathCapture` unpack
 * a second *obfuscated* server jar onto the launch classpath.
 */
object JarMerger {

    /**
     * Output-format version — folded into the merged jar's cache name.
     * The merged artifact is produced inside an artifact transform keyed
     * on a constant marker jar, so nothing else invalidates a stale
     * merge: bump this whenever merge semantics change (v1 = pre
     * META-INF strip).
     */
    const val FORMAT_VERSION = "v2"

    /** Merge [server] onto [client] → [out]. */
    fun merge(client: Path, server: Path, out: Path) {
        val entries = linkedMapOf<String, ByteArray>()

        fun read(jar: Path) {
            ZipInputStream(jar.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && !e.name.startsWith("META-INF/")) {
                        entries[e.name] = zin.readBytes()
                    }
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
