package xyz.wagyourtail.unimined.transform.jarmod.task

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Merges every jar in [jarModFiles] over [inputArtifact], emitting the
 * combined dev jar — 1.x `JarModMinecraftTransformer` semantics:
 * jarmod entries overwrite existing ones; `META-INF/MANIFEST.MF` and
 * signature files (`.SF`/`.RSA`/`.DSA`) are never copied; the rest of
 * `META-INF/` survives only while [deleteMetaInf] is off.
 */
abstract class JarModMergeTask : TransformMinecraftTask() {

    @get:InputFiles
    abstract val jarModFiles: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val deleteMetaInf: Property<Boolean>

    init {
        deleteMetaInf.convention(false)
    }

    @TaskAction
    fun run() {
        val inputs = jarModFiles.files.sortedBy { it.name }
        logger.lifecycle(
            "[Unimined/$path] merging {} jarmod entries into {}",
            inputs.size, inputArtifact.get().asFile.name
        )
        outputArtifact.get().asFile.outputStream().use { fos ->
            ZipOutputStream(fos).use { out ->
                val written = HashSet<String>()
                fun put(file: java.io.File, stripMetaInf: Boolean) {
                    ZipFile(file).use { zip ->
                        for (entry in zip.entries()) {
                            val name = entry.name
                            if (name == "META-INF/MANIFEST.MF" ||
                                name.endsWith(".SF") ||
                                name.endsWith(".RSA") ||
                                name.endsWith(".DSA")
                            ) continue
                            if (stripMetaInf && name.startsWith("META-INF/")) continue
                            if (entry.isDirectory || !written.add(name)) continue
                            out.putNextEntry(ZipEntry(name).apply { time = entry.time })
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
                put(inputArtifact.get().asFile, false)
                inputs.forEach { put(it, deleteMetaInf.get()) }
            }
        }
    }

}
