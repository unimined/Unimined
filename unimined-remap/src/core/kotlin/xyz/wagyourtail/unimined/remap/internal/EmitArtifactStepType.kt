package xyz.wagyourtail.unimined.remap.internal

import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import xyz.wagyourtail.commonskt.jvm.moveAtomicTo
import xyz.wagyourtail.commonskt.jvm.tempSibling
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.EmitArtifactStepSpec
import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.reflect.KClass

/**
 * `emit` step — the pipeline-intrinsic tap backing
 * [EmitArtifactStepSpec]. Not an engine: it writes the artifact state
 * at its pipeline position to the spec's output jar, side-by-side with
 * the group output. Being [FusibleJarStep] means it never splits a
 * fused group and costs no extra traversal — the emitted jar receives
 * each entry post-preceding-steps and pre-following-steps, which is
 * exactly the mid artifact a consumer at that boundary wants.
 */
class EmitArtifactStepType : RemapStepType<EmitArtifactStepSpec> {

    override val name = "core"
    override val stepName = "emit"
    override val specType: KClass<EmitArtifactStepSpec> = EmitArtifactStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: EmitArtifactStepSpec, ctx: StepContext): ArtifactStep =
        EmitArtifactStep(Paths.get(spec.output))

}

private class EmitArtifactStep(
    private val output: Path
) : FusibleJarStep {

    private var tmp: Path? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private var creator: ParallelScatterZipCreator? = null

    override fun prepareFused(input: Path, ctx: StepContext) {
        output.parent?.createDirectories()
        tmp = output.tempSibling()
        // deflate fans out across the traversal — entry bytes arrive
        // already in memory, so the pool compresses them as they stream
        // in instead of serializing a single-threaded deflate pass into
        // the close (~9 s on a merged MC jar). The scatter creator
        // writes in submission order → bytes stay deterministic, and
        // source entry times are preserved like the group output.
        val p = java.util.concurrent.Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        )
        pool = p
        creator = ParallelScatterZipCreator(p)
    }

    override fun visitDirectory(entry: FusedJarEntry, ctx: StepContext): Boolean {
        add(entry.name, entry.time, ByteArray(0))
        return true
    }

    override fun visitEntry(entry: FusedJarEntry, ctx: StepContext): FusedJarEntry? {
        add(entry.name, entry.time, entry.bytes)
        return entry
    }

    private fun add(name: String, time: Long, bytes: ByteArray) {
        creator!!.addArchiveEntry(
            ZipArchiveEntry(name).apply {
                method = ZipArchiveEntry.DEFLATED
                this.time = time
            }
        ) { java.io.ByteArrayInputStream(bytes) }
    }

    override fun endFused(ctx: StepContext) {
        val t = tmp!!
        val p = pool!!
        try {
            ZipArchiveOutputStream(Files.newOutputStream(t)).use { zout ->
                creator!!.writeTo(zout)
            }
            // emitted jars are produced artifacts — strip signing
            // metadata like the group output gets (guarded: ~ms scan
            // on unsigned output)
            JarSignatureStripper.sanitize(t)
            t.moveAtomicTo(output)
        } finally {
            p.shutdown()
            Files.deleteIfExists(t)
        }
    }

}
