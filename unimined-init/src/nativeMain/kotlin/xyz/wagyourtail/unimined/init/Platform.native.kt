package xyz.wagyourtail.unimined.init

import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

private val client by lazy { HttpClient(Curl) }

internal actual fun httpGet(url: String): ByteArray = runBlocking {
    client.get(url).bodyAsBytes()
}

internal actual fun extractFromZip(
    zipPath: String, prefix: String, outDir: String, overwrite: Boolean
): List<String> = throw UnsupportedOperationException(
    "--template zip extraction is JVM-only — generate from flags instead"
)

@OptIn(ExperimentalForeignApi::class)
internal actual fun setExecutable(path: String) {
    platform.posix.chmod(path, 0b111_111_111u.convert())
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun epochMillis(): Long =
    platform.posix.time(null) * 1000L

@OptIn(ExperimentalForeignApi::class)
actual fun eprintln(msg: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", msg)
}

@OptIn(ExperimentalForeignApi::class)
actual fun userHome(): String =
    platform.posix.getenv("HOME")?.toKString() ?: cwd()

actual fun cwd(): String =
    FileSystem.SYSTEM.canonicalize(".".toPath()).toString()

internal actual val fileSystem: FileSystem get() = FileSystem.SYSTEM
