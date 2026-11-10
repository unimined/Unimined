package xyz.wagyourtail.unimined.init

import okio.FileSystem
import okio.NodeJsFileSystem

internal actual val fileSystem: FileSystem get() = NodeJsFileSystem

/** Node builtins — the CLI runs under `js(IR)+nodejs`, so plain
 *  `require` externals work everywhere node ≥16 does. */
@JsName("require")
private external fun nodeRequire(module: String): dynamic

private fun childProcess() = nodeRequire("child_process")
private fun nodeFs() = nodeRequire("fs")
private fun nodeOs() = nodeRequire("os")

internal actual fun httpGet(url: String): ByteArray {
    // sync fetch doesn't exist on node — shell out to curl, which the
    // CLI's target platforms all have (same dep the fat jar path uses).
    val out = childProcess().execSync("curl -sfL \"$url\"")
    val s: String = out.toString()
    return s.encodeToByteArray()
}

internal actual fun extractFromZip(
    zipPath: String, prefix: String, outDir: String, overwrite: Boolean
): List<String> =
    throw UnsupportedOperationException(
        "--templates zip extraction is JVM-only for now")

internal actual fun setExecutable(path: String) {
    nodeFs().chmodSync(path, 0x1ED)
}

internal actual fun epochMillis(): Long = (js("Date.now()") as Double).toLong()

actual fun cwd(): String = js("process.cwd()") as String

actual fun eprintln(msg: String) {
    js("console.error(msg)")
}

actual fun userHome(): String = nodeOs().homedir() as String
