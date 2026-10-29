package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.jarmod.task.JarModMergeTask

/**
 * Environment wiring for `loader("jarmod", ...)` environments —
 * 1.x `JarModMinecraftTransformer`/`JarModAgentMinecraftTransformer`
 * split into a merge step (jar contents -> dev MC jar) plus, in a
 * follow-up, JarModAgent runtime wiring.
 *
 * Two per-env configurations, matching 1.x:
 *  - `jarMod<env>`: user-declared jars merged into the Minecraft jar
 *    (any dependency type — maven coords, project(), files(), ...).
 *  - `jarModAgent<env>`: the JarModAgent artifact itself, resolved for
 *    compile-time transforms/run wiring (phase b).
 */
internal object JarModEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val envId = environment.environmentId
        val jarMod = project.configurations.maybeCreate("jarMod${envId.replaceFirstChar { it.uppercaseChar() }}").apply {
            isCanBeConsumed = false
        }
        val jarModAgent = project.configurations
            .maybeCreate("jarModAgent${envId.replaceFirstChar { it.uppercaseChar() }}").apply {
                isCanBeConsumed = false
            }
        project.afterEvaluate {
            environment.loaderSpec.orNull?.let { spec ->
                project.dependencies.add(
                    jarModAgent.name, "xyz.wagyourtail.unimined:jarmod-agent:${spec.version}:all"
                )
            }
        }

        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(envId)
        envSpec.transformer(
            JarModMergeTransformer(project, envId, jarMod)
        )
        // JarModAgent on the run classpath: -javaagent + system props
        // (1.x JarModAgentMinecraftTransformer run config)
        envSpec.runMutators += org.gradle.api.Action { config ->
            val jmaFile = jarModAgent.resolve().first { it.extension == "jar" }
            config.jvmArgs += "-javaagent:${jmaFile.absolutePath}"
            val transforms = JarModEnvState.transforms(envId)
            if (transforms.isNotEmpty()) {
                config.jvmArgs += "-Djma.transformers=" +
                    transforms.joinToString(java.io.File.pathSeparator)
            }
            val priority = JarModEnvState.priorityClasspath(envId)
            if (priority.isNotEmpty()) {
                config.jvmArgs += "-Djma.priorityClasspath=" +
                    priority.joinToString(java.io.File.pathSeparator)
            }
        }
    }

}
