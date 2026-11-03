package xyz.wagyourtail.unimined.init

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path as NioPath

internal actual fun httpGet(url: String): ByteArray =
    URI.create(url).toURL().openConnection().run {
        connectTimeout = 15_000; readTimeout = 30_000
        getInputStream().use { it.readBytes() }
    }

internal actual fun extractFromZip(
    zipPath: String, prefix: String, outDir: String, overwrite: Boolean
): List<String> {
    val out = NioPath.of(outDir).normalize()
    val written = mutableListOf<String>()
    java.util.zip.ZipFile(zipPath).use { zf ->
        zf.entries().asSequence()
            .filter { it.name.startsWith(prefix) && !it.isDirectory }
            .forEach { e ->
                val rel = e.name.removePrefix(prefix)
                val target = out.resolve(rel).normalize()
                require(target.startsWith(out)) {
                    "template entry escapes outDir: ${e.name}"
                }
                if (Files.exists(target) && !overwrite) {
                    error("refusing to overwrite $target")
                }
                target.parent?.let { Files.createDirectories(it) }
                zf.getInputStream(e).use { input ->
                    Files.copy(input, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                written.add(target.toString())
            }
    }
    return written
}

internal actual fun setExecutable(path: String) {
    java.io.File(path).setExecutable(true, false)
}

internal actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun eprintln(msg: String) = System.err.println(msg)

actual fun userHome(): String = System.getProperty("user.home")

actual fun cwd(): String =
    NioPath.of("").toAbsolutePath().toString()

internal actual val fileSystem: FileSystem get() = FileSystem.SYSTEM
