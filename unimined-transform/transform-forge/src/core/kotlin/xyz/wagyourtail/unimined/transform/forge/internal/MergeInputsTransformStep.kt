package xyz.wagyourtail.unimined.transform.forge.internal

import xyz.wagyourtail.unimined.transform.step.TransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/**
 * `forge:mergeInputs` — merge named env-artifact inputs (resolved via
 * [TransformStepContext.inputs]) into the pipeline jar.
 *
 * The pipeline input is the base; each bound input overlays it —
 * later-declared bindings win on name collisions, mirroring
 * `jarmod:merge`'s "jarmod entries win" and `JarMerger`'s
 * "server entries win" precedence. Signature material
 * (`.SF`/`.RSA`/`.DSA`/`.EC` under `META-INF`) is dropped from
 * every source — a
 * merge invalidates signatures regardless — and aux manifests never
 * clobber the base `META-INF/MANIFEST.MF`.
 *
 * Member-level sided merging (1.x `ClassMerger` `@SideOnly`
 * marking) is intentionally out of scope: split-side MC assembly is
 * the provider `JarMerger`'s job at `ArtifactKey.MERGED`; this step
 * is for declared pipelines overlaying whole jars.
 */
object MergeInputsTransformStep : TransformStep, Serializable {

    private const val serialVersionUID = 1L

    private fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        return name.endsWith(".SF") || name.endsWith(".RSA") ||
                name.endsWith(".DSA") || name.endsWith(".EC")
    }

    override fun transform(input: Path, ctx: TransformStepContext): Path {
        // declared order preserved — later bindings overlay earlier
        val aux = ctx.inputs.values.flatten().map { it.toPath() }
        if (aux.isEmpty()) return input
        val out = ctx.workDir.resolve("mergeInputs.jar")
        Files.createDirectories(ctx.workDir)
        ZipOutputStream(out.outputStream()).use { zos ->
            val written = HashSet<String>()
            // first-write wins, so write aux (reversed for
            // last-declared-wins) then the base input last
            for (src in aux.asReversed() + listOf(input)) {
                val isBase = src == input
                ZipFile(src.toFile()).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (isSignatureEntry(name)) continue
                        if (!isBase && name == "META-INF/MANIFEST.MF") continue
                        if (!written.add(name)) continue
                        if (entry.isDirectory) {
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            zip.getInputStream(entry).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
        return out
    }

}
