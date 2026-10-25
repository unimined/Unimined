package xyz.wagyourtail.unimined.decompiler.vineflower

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.source.spi.DecompilerEngine
import xyz.wagyourtail.unimined.source.spi.DecompileSpec
import xyz.wagyourtail.unimined.source.task.DecompileTask

/**
 * Vineflower decompiler engine — the default `genSources`
 * implementation. `supports` is unconditional; its [priority] wins
 * capability selection, so other engines need an explicit
 * `source.engine` override to displace it.
 */
class VineflowerDecompilerEngine : DecompilerEngine {

    override val name: String = "vineflower"

    override val priority: Int = 100

    override fun supports(spec: DecompileSpec): Boolean = true

    override fun createTask(
        project: Project,
        name: String,
        spec: DecompileSpec
    ): TaskProvider<out DecompileTask> {
        val decompilerClasspath = project.configurations.detachedConfiguration(
            project.dependencies.create("org.vineflower:vineflower:$VINEFLOWER_VERSION")
        )
        return project.tasks.register(name, VineflowerDecompileTask::class.java) { t ->
            t.inputJar.set(spec.input)
            t.classpath.from(spec.classpath)
            t.options.set(spec.options)
            t.decompilerClasspath.from(decompilerClasspath)
        }
    }

    companion object {
        const val VINEFLOWER_VERSION = "1.12.0"
    }

}
