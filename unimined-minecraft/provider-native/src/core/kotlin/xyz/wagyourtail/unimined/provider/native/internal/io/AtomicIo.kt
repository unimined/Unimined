package xyz.wagyourtail.unimined.provider.native.internal.io

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Temp-sibling + atomic-move write helpers: produced artifacts must never
 *  leave a truncated/partial file at the destination (issue #78 — a crashed
 *  producer's empty jar must not become a resolvable artifact). */
internal object AtomicIo {

    /** Move [src] onto [dest] atomically where the FS supports it,
     *  replacing any existing file. */
    fun moveAtomic(src: Path, dest: Path) {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** A fresh temp file next to [dest] for write-then-move producers. */
    fun tempSibling(dest: Path): Path {
        dest.parent?.let { Files.createDirectories(it) }
        return Files.createTempFile(dest.parent, ".${dest.fileName}.", ".tmp")
    }

    /** Copy [src] to a temp sibling of [dest], then atomic-move it in. */
    fun copyAtomic(src: Path, dest: Path) {
        val tmp = tempSibling(dest)
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
            moveAtomic(tmp, dest)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
