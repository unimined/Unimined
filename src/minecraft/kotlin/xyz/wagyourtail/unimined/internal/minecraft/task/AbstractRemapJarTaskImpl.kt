package xyz.wagyourtail.unimined.internal.minecraft.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.getField
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import kotlin.io.path.*

@Suppress("UNCHECKED_CAST")
abstract class AbstractRemapJarTaskImpl @Inject constructor(@get:Internal val provider: MinecraftConfig): AbstractRemapJarTask() {

    override fun devNamespace(namespace: String) {
        val delegate: FinalizeOnRead<Namespace> = AbstractRemapJarTask::class.getField("devNamespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.checkedNs(namespace) })
    }

    override fun prodNamespace(namespace: String) {
        val delegate: FinalizeOnRead<Namespace> = AbstractRemapJarTask::class.getField("prodNamespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.checkedNs(namespace) })
    }

    @TaskAction
    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    fun run() {
        val prodNs = prodNamespace ?: provider.mcPatcher.prodNamespace!!
        val devNs = devNamespace ?: provider.mappings.devNamespace!!

        val inputFile = provider.mcPatcher.beforeRemapJarTask(this, inputFile.get().asFile.toPath())

        if (devNs == prodNs) {
            project.logger.lifecycle("[Unimined/RemapJar ${this.path}] detected empty remap path, jumping to after remap tasks")
            provider.mcPatcher.afterRemapJarTask(this, inputFile)
            afterRemap(inputFile)
            return
        }

        project.logger.lifecycle("[Unimined/RemapJar ${this.path}] remapping output ${inputFile.name} from $devNs to $prodNs")
        val prodMapped = temporaryDir.toPath().resolve("${inputFile.nameWithoutExtension}-temp-${prodNs}.jar")
        prodMapped.deleteIfExists()

        val mc = provider.getMinecraft(devNs)

        val classpath = provider.mods.getClasspathAs(
            devNs,
            provider.sourceSet.compileClasspath.files
        ).map { it.toPath() }.filter { it.exists() && !provider.isMinecraftJar(it) }

        project.logger.debug("[Unimined/RemapJar ${path}] classpath: ")
        classpath.forEach {
            project.logger.debug("[Unimined/RemapJar ${path}]    $it")
        }

        doRemap(inputFile, prodMapped, devNs, prodNs, (classpath + listOf(mc)).toTypedArray())

        project.logger.info("[Unimined/RemapJar ${path}] after remap tasks started ${System.currentTimeMillis()}")
        provider.mcPatcher.afterRemapJarTask(this, prodMapped)
        afterRemap(prodMapped)
        project.logger.info("[Unimined/RemapJar ${path}] after remap tasks finished ${System.currentTimeMillis()}")
    }

    private fun afterRemap(afterRemapJar: Path) {
        // copy into output
        from(project.zipTree(afterRemapJar))
        copy()
    }

    protected abstract fun doRemap(
        from: Path,
        target: Path,
        fromNs: Namespace,
        toNs: Namespace,
        classpathList: Array<Path>
    )
}