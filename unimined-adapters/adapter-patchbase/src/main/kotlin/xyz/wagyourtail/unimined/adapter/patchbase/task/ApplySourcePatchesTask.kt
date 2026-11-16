package xyz.wagyourtail.unimined.adapter.patchbase.task

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.util.failProblem
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import xyz.wagyourtail.unimined.adapter.patchbase.PatchBaseEnvExtension
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * Applies a git/mache-style unified-diff patch set onto a base source
 * tree — the apply half of [PatchBaseEnvExtension.patchBaseCreator]'s
 * loop. Local to the adapter because `patchbase:2.0.0`'s own
 * `ApplySourcePatchTask` cannot apply its own output: it parses the
 * patch text's `lines()` verbatim, whose trailing `""` element
 * `parseUnifiedDiff` absorbs as a phantom context line, growing every
 * patch's last hunk by one line and failing content verification on
 * any newline-terminated patch file. (Fixed upstream in `2.0.1`; this
 * task is kept for the `failProblem` diagnostics and verified behavior.)
 *
 * Walks [patchDir]: `*.patch` files are applied to the first [sources]
 * entry (directory or jar) containing the patched file's path;
 * everything else copies verbatim — the same overlay layout
 * `CreateSourcePatchTask` emits, where net-new files travel as plain
 * `*.java` beside the diffs. Only patch-covered files land in
 * [outputDir].
 */
@DisableCachingByDefault(because =
    "materializes a patch-set overlay into the working tree — " +
    "the patch dir is the source of truth")
abstract class ApplySourcePatchesTask : org.gradle.api.DefaultTask() {

    /** Directory holding the patch set — `*.patch` unified diffs plus
     *  verbatim files, keyed to the base source tree. */
    @get:Input
    abstract val patchDir: Property<File>

    /** Where patched files are written — wiped and repopulated on
     *  every run. */
    @get:Input
    abstract val outputDir: Property<File>

    /** Base source trees the diffs apply against — directories or
     *  sources jars (e.g. the `gen<Env>Sources` output); first match
     *  wins. */
    @get:Input
    abstract val sources: Property<FileCollection>

    private fun sourceBytes(relative: Path): ByteArray? {
        val entry = relative.joinToString("/") { it.toString() }
        for (source in sources.get()) {
            val sourcePath = source.toPath()
            if (sourcePath.isDirectory()) {
                val file = sourcePath.resolve(relative)
                if (file.exists()) return file.readBytes()
            } else {
                try {
                    ZipFile(source).use { zip ->
                        zip.getEntry(entry)?.let { e ->
                            return zip.getInputStream(e).readBytes()
                        }
                    }
                } catch (e: java.util.zip.ZipException) {
                    // not a zip — try the next source root
                }
            }
        }
        return null
    }

    /** Apply a unified diff. [patchText]'s `lines()` keeps a trailing
     *  `""` element for every newline-terminated file; that element is
     *  not part of the patch and must be dropped before
     *  [UnifiedDiffUtils.parseUnifiedDiff] or the last hunk gains a
     *  phantom context line (`patchbase:2.0.0`'s apply bug). */
    fun applyDiff(originalText: String, patchText: String): String {
        val patch = UnifiedDiffUtils.parseUnifiedDiff(
            patchText.lines().dropLastWhile { it.isEmpty() })
        val patched = DiffUtils.patch(originalText.lines(), patch)
        return patched.joinToString("") { "$it\n" }
    }

    @OptIn(ExperimentalPathApi::class)
    @TaskAction
    fun apply() {
        val output = outputDir.get().toPath()
        if (output.exists()) output.deleteRecursively()

        val patch = patchDir.get().toPath()
        for (path in patch.walk()) {
            val relative = path.relativeTo(patch)
            val targetParent = output.resolve(relative.parent)
            targetParent.createDirectories()
            if (path.extension != "patch") {
                // copy verbatim — net-new sources, resources, etc.
                path.copyTo(targetParent.resolve(relative.fileName))
            } else {
                val targetRel = relative.resolveSibling(
                    relative.nameWithoutExtension)
                val original = sourceBytes(targetRel)
                    ?: project.failProblem("patchbase/missing-file",
                        "unimined: cannot apply patch to non-existent " +
                            "file: $relative")
                targetParent.resolve(relative.nameWithoutExtension)
                    .writeText(applyDiff(
                        original.decodeToString(), path.readText()))
            }
        }
    }

}
