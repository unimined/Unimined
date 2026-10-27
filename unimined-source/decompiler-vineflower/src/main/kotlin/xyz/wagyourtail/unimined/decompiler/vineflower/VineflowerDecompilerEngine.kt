package xyz.wagyourtail.unimined.decompiler.vineflower

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.source.spi.DecompilerEngine
import xyz.wagyourtail.unimined.source.spi.DecompileSpec
import xyz.wagyourtail.unimined.source.spi.ModSourcesTransformSpec
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
        return project.tasks.register(name, VineflowerDecompileTask::class.java) { t ->
            t.inputJar.set(spec.input)
            t.classpath.from(spec.classpath)
            t.options.set(spec.options)
            t.decompilerClasspath.from(decompilerClasspath(project))
        }
    }

    override fun registerModSourcesTransform(
        project: Project,
        spec: ModSourcesTransformSpec
    ) {
        project.dependencies.registerTransform(VineflowerDecompileTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, spec.fromArtifactType
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, spec.toArtifactType
            )
            t.parameters { p ->
                p.decompilerClasspath.from(decompilerClasspath(project))
                p.referenceClasspath.from(spec.classpath)
                p.options.set(spec.options)
            }
        }
    }

    /** Shared resolved vineflower classpath — one configuration per
     *  project regardless of how many mod-bucket transforms register. */
    private fun decompilerClasspath(project: Project) =
        project.configurations.findByName(DECONF_NAME)
            ?: project.configurations.create(DECONF_NAME) { conf ->
                conf.isCanBeConsumed = false
                conf.isCanBeResolved = true
                project.dependencies.add(
                    DECONF_NAME, "org.vineflower:vineflower:$VINEFLOWER_VERSION"
                )
            }

    companion object {
        const val VINEFLOWER_VERSION = "1.12.0"
        private const val DECONF_NAME = "uniminedVineflower"
    }

}
