package xyz.wagyourtail.unimined.internal.minecraft.task

import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.task.RemapSourcesJarTask
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.deleteRecursively
import xyz.wagyourtail.unimined.util.getField
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.Deflater
import javax.inject.Inject
import kotlin.io.path.*

abstract class RemapSourcesJarTaskImpl @Inject constructor(provider: MinecraftConfig):
    AbstractRemapJarTaskImpl(provider), RemapSourcesJarTask {

    @OptIn(ExperimentalPathApi::class)
    override fun doRemap(
        from: Path,
        target: Path,
        fromNs: Namespace,
        toNs: Namespace,
        classpathList: Array<Path>
    ) {
        // source-remap seems to be broken when reading/writing from a jar, so copy them to/from temp dirs
        val output = temporaryDir.resolve(toNs.name).toPath().apply {
            if(this.exists()) deleteRecursively()
            createDirectories()
        }

        val input = temporaryDir.resolve(fromNs.name).toPath().apply {
            if(this.exists()) deleteRecursively()
            createDirectories()

            project.copy {
                it.from(project.zipTree(from))
                it.into(this)
            }
        }

        provider.sourceProvider.sourceRemapper.remap(
            mapOf(input to output),
            project.files(*classpathList),
            fromNs,
            toNs,
            specConfig = {
                standardOutput = temporaryDir.resolve("remap-${fromNs}-to-${toNs}.log").outputStream()
            }
        )

        // copy non-source files directly
        input.walk().filter { it.extension != "java" && it.extension != "kt" }.forEach { file ->
            val name = input.relativize(file).toString().replace('\\', '/')
            val targetFile = output.resolve(name)
            targetFile.parent.createDirectories()
            file.copyTo(targetFile, overwrite = true)
        }

        JarOutputStream(target.toFile().outputStream()).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)
            output.walk().forEach { file ->
                val name = output.relativize(file).toString().replace('\\', '/')
                zos.putNextEntry(JarEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

}