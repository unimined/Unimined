package xyz.wagyourtail.unimined.api.util

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    try {
        produce(tmp)
        try {
            Files.move(tmp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, this, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}
