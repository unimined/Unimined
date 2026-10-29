package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.transform.jarmod.task.JarModMergeTask
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask

/**
 * Pipeline step merging the env's `jarMod` configuration into the dev
 * Minecraft jar — the 1.x jarmod `transform` FileSystem ops as a
 * task-backed transform (fingerprinted: the config's resolved files
 * are the task's inputs).
 */
internal class JarModMergeTransformer(
    project: Project,
    environmentId: String,
    jarMod: Configuration
) : MinecraftTransformer {

    override val transformTaskProvider: TaskProvider<out TransformMinecraftTask> =
        project.tasks.register(
            "jarModMerge${environmentId.replaceFirstChar { it.uppercaseChar() }}", JarModMergeTask::class.java
        ) {
            it.jarModFiles.from(jarMod)
        }

    override fun configure(ctx: TransformContext) {
        ctx.emit("jar", transformTaskProvider.flatMap { it.outputArtifact })
    }

}
