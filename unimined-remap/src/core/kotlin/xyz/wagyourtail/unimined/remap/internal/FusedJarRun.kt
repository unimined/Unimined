package xyz.wagyourtail.unimined.remap.internal

import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/**
 * Executes a consecutive run of [FusibleJarStep]s as one jar traversal:
 * each step's [FusibleJarStep.prepareFused] sees the shared input once,
 * then every entry flows through the visitors in declaration order —
 * one open, one ordered pass, one write — instead of a full jar round
 * trip per step. Directory entries and per-entry timestamps pass
 * through verbatim so output stays deterministic.
 */
internal object FusedJarRun {

    /**
     * Fuse [steps] over [input] into a new jar under [ctx]'s workDir.
     * [intermediate] outputs (a non-fused step or another group follows)
     * are written STORED — the bytes are already in hand so size/crc are
     * free, and skipping deflate+inflate on a transient jar is pure win.
     * The final artifact keeps DEFLATED.
     */
    fun run(
        input: Path,
        steps: List<FusibleJarStep>,
        ctx: StepContext,
        intermediate: Boolean = false
    ): Path {
        steps.forEach { it.prepareFused(input, ctx) }
        Files.createDirectories(ctx.workDir)
        val output = Files.createTempDirectory(ctx.workDir, "fused")
            .resolve(input.fileName.toString())
        ZipFile(input.toFile()).use { zip ->
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                for (e in zip.entries()) {
                    if (e.isDirectory) {
                        val dir = FusedJarEntry(e.name, ByteArray(0), e.time)
                        if (steps.all { it.visitDirectory(dir, ctx) }) {
                            out.putNextEntry(JarEntry(e.name).apply { time = e.time })
                            out.closeEntry()
                        }
                        continue
                    }
                    var cur: FusedJarEntry? =
                        FusedJarEntry(e.name, zip.getInputStream(e).readBytes(), e.time)
                    for (step in steps) {
                        val entry = cur ?: break
                        cur = step.visitEntry(entry, ctx)
                    }
                    if (cur != null) {
                        val entry = JarEntry(cur.name).apply { time = e.time }
                        if (intermediate) {
                            entry.method = java.util.zip.ZipEntry.STORED
                            entry.size = cur.bytes.size.toLong()
                            entry.compressedSize = cur.bytes.size.toLong()
                            entry.crc = java.util.zip.CRC32()
                                .also { it.update(cur.bytes) }.value
                        }
                        out.putNextEntry(entry)
                        out.write(cur.bytes)
                        out.closeEntry()
                    }
                }
            }
        }
        steps.forEach { it.endFused(ctx) }
        return output
    }

}
