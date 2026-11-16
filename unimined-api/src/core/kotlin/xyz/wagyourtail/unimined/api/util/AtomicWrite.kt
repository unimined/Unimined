package xyz.wagyourtail.unimined.api.util

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Produce a file at [this] by writing to a temp sibling and moving it
 * into place atomically — a killed or interrupted producer must not
 * leave a partial artifact at the final path: existence-gated caches
 * and shared artifact transforms treat "file present" as "artifact
 * complete", so a truncated jar could be served to later runs (#78).
 *
 * The temp file is created in the same directory so the move stays on
 * one filesystem and [StandardCopyOption.ATOMIC_MOVE] applies. On
 * failure the temp is deleted and [this] is left untouched.
 *
 * @param produce writes the complete content to the given temp path
 */
fun Path.writeAtomically(produce: (Path) -> Unit) {
    // `parent` is null for relative paths — resolve it so the temp
    // always shares the target's filesystem (ATOMIC_MOVE requires it)
    val dir = parent ?: toAbsolutePath().parent
    val tmp = Files.createTempFile(dir, ".${fileName}-", ".tmp")
    // createTempFile reserves a unique name then we drop it — produce
    // gets a *non-existent* path so `Files.copy` (which refuses to
    // overwrite) and "tool refuses existing empty file" both work
    Files.delete(tmp)
    try {
        produce(tmp)
        // a produce that writes nothing publishes nothing — callers'
        // exists-gates retry next run (absent = incomplete). Tools
        // that *do* create the file publish it even when 0-byte.
        if (Files.exists(tmp)) {
            try {
                Files.move(tmp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, this, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/**
 * Inter-process cache lock — an OS-level `FileLock` on a
 * `<target>.lock` sibling, held for [block]. Concurrent Gradle
 * daemons (same gradle-user-home cache) serialize on the file:
 * the second waits for the first's publish instead of racing the
 * produce. Lock files are never deleted — a stale lock file is
 * harmless, deleting it reopens the race.
 */
fun <T> Path.withCacheLock(block: () -> T): T {
    val dir = parent ?: toAbsolutePath().parent
    dir?.let { Files.createDirectories(it) }
    val lockPath = dir.resolve(".${fileName}.lock")
    java.nio.channels.FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE
    ).use { ch ->
        ch.lock().use { return block() }
    }
}

/**
 * Existence-gated cache produce, safe across concurrent daemons:
 * under a [withCacheLock], skip when [this] exists (unless
 * [force]), produce into a temp sibling, then atomic-move into
 * place — readers can never observe a partial artifact and
 * "file present" remains equivalent to "produce completed".
 * [produce] receives the temp path — subprocesses and stream
 * copies write there directly.
 */
fun Path.produceCached(
    force: Boolean = false,
    produce: (Path) -> Unit
): Path = withCacheLock {
    if (force || !Files.exists(this)) writeAtomically(produce)
    this
}

/**
 * [produceCached] for directory targets — produce into a temp
 * sibling dir and move it into place. Readers see either no dir
 * or a complete one; never a half-extracted tree.
 */
fun Path.produceDirCached(
    force: Boolean = false,
    produce: (Path) -> Unit
): Path = withCacheLock {
    if (force || !Files.exists(this)) {
        val dir = parent ?: toAbsolutePath().parent
        val tmp = Files.createTempDirectory(dir, ".${fileName}-")
        try {
            produce(tmp)
            try {
                Files.move(tmp, this, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, this)
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
    this
}
