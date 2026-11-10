package xyz.wagyourtail.unimined.adapter.patchbase

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Optional PatchBase adapter — per-env `patchBase<env>` configuration
 * whose resolved jars feed [PatchBaseProcessor] on the env jar
 * pipeline (pre-remap, prod namespace). Apply alongside
 * `xyz.wagyourtail.unimined`; declare patch deps via
 * `env.patchBase("g:a:v")` or the `patchBase<env>` configuration.
 *
 * PatchBase's installer is consumed as the MC-free
 * `xyz.wagyourtail.unimined:patchbase:*:installer` artifact — unimined
 * carries no patch machinery itself.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val transform = project.extensions.getByType(TransformExtension::class.java)

        minecraft.environments.configureEach { env ->
            val envId = env.environmentId
            val patchBase = project.configurations.create(
                "patchBase${envId.replaceFirstChar { it.uppercase() }}") {
                it.isCanBeConsumed = false
                it.isCanBeResolved = true
                it.description =
                    "PatchBase patch jars applied to the '$envId' MC jar"
            }
            env.extensions.create(
                "patchBase", PatchBaseEnvExtension::class.java,
                project, env, patchBase)

            val envSpec = transform.sourceSet(envId)
            envSpec.envArtifactBindings["patchbase"] = {
                patchBase.files.map { it.toPath() }
            }
            envSpec.jarProcessors += PatchBaseProcessor()
        }
    }

}
