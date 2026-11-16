package xyz.wagyourtail.unimined.source.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.source.internal.GlConstantFixer
import java.nio.file.Files

/**
 * Applies decompiler-cleanup fixups to user Java sources in place —
 * RFG `applyDecompilerCleanupToMain` parity. Currently the
 * `gl_constants.json`-backed [GlConstantFixer] pass: raw numeric
 * literals inside `org.lwjgl.opengl` calls become named `GLxx`
 * constants. Opt-in by invocation (`cleanupSources` /
 * `cleanup<Env>Sources`); only files the fixer actually changes are
 * written, and writes are atomic so a killed run can't leave a
 * truncated source file.
 */
@DisableCachingByDefault(because = "mutates user sources in place — nothing cacheable")
abstract class DecompCleanupTask : DefaultTask() {

    /** Source roots to clean — the bound source set's `java.srcDirs`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    /** Build dir — env wiring injects generated/patched sources
     *  (under here) as java srcDirs; those are build outputs and
     *  are excluded from in-place rewriting. */
    @get:Internal
    abstract val buildDir: DirectoryProperty

    @TaskAction
    fun cleanup() {
        val buildRoot = buildDir.get().asFile.toPath()
        for (dir in sourceDirs.files) {
            if (!dir.isDirectory) continue
            if (dir.toPath().startsWith(buildRoot)) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    val source = file.readText()
                    val patched = GlConstantFixer.fixOGL(source)
                    if (patched != source) {
                        logger.info("[Unimined/decompCleanup] patched ${file.path}")
                        file.toPath().writeAtomically { tmp ->
                            Files.writeString(tmp, patched)
                        }
                    }
                }
        }
    }

}
