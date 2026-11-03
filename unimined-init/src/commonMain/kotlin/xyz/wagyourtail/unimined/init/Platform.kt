package xyz.wagyourtail.unimined.init

import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Platform hooks — JVM (jvmMain) and Kotlin/Native (nativeMain)
 * actuals. Everything else uses the kotlinx-io helpers below so the
 * generator runs identically on both targets.
 */

/** Blocking HTTP GET body — java.net on JVM, ktor-curl on Native. */
internal expect fun httpGet(url: String): ByteArray

/** Extract entries under [prefix] of [zipPath] into [outDir]. */
internal expect fun extractFromZip(
    zipPath: String, prefix: String, outDir: String, overwrite: Boolean
): List<String>

/** `chmod +x` (JVM `setExecutable`, Native `chmod`). */
internal expect fun setExecutable(path: String)

/** Wall-clock millis — cache timestamps. */
internal expect fun epochMillis(): Long

/** Current working directory absolute path. */
expect fun cwd(): String

/** stderr println. */
expect fun eprintln(msg: String)

/** User home directory. */
expect fun userHome(): String

// ---- shared file helpers (kotlinx-io, common to both targets) ----

object Io {
    fun path(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString("/")
            .replace("//", "/")

    fun parent(path: String): String? =
        path.substringBeforeLast('/', "").ifEmpty { null }

    fun name(path: String): String = path.substringAfterLast('/')

    fun mkdirs(path: String) {
        FileSystem.SYSTEM.createDirectories(path.toPath(), mustCreate = false)
    }

    fun exists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())

    fun isEmptyDir(path: String): Boolean =
        exists(path) && FileSystem.SYSTEM.list(path.toPath()).isEmpty()

    fun writeText(path: String, text: String) {
        parent(path)?.let { mkdirs(it) }
        FileSystem.SYSTEM.write(path.toPath()) { writeUtf8(text) }
    }

    fun writeBytes(path: String, bytes: ByteArray) {
        parent(path)?.let { mkdirs(it) }
        FileSystem.SYSTEM.write(path.toPath()) { write(bytes) }
    }

    fun readText(path: String): String =
        FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }

    fun readBytes(path: String): ByteArray =
        FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }

    fun delete(path: String) {
        if (exists(path)) FileSystem.SYSTEM.delete(path.toPath())
    }

    fun resolve(vararg parts: String): String =
        FileSystem.SYSTEM.canonicalize(path(*parts).toPath()).toString()
}
