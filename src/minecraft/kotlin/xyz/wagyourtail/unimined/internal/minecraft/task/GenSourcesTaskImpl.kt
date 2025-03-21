package xyz.wagyourtail.unimined.internal.minecraft.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.minecraft.task.GenSourcesTask
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import javax.inject.Inject
import kotlin.io.path.nameWithoutExtension

abstract class GenSourcesTaskImpl @Inject constructor(@get:Internal val provider: MinecraftProvider) : GenSourcesTask() {

    @TaskAction
    fun run() {
        val mcDevFile = provider.getMcDevFile()
        val sourcesJar = mcDevFile.resolveSibling("${mcDevFile.nameWithoutExtension}-sources.jar")
        val linemappedJar = mcDevFile.resolveSibling("${mcDevFile.nameWithoutExtension}-linemapped.jar")

        // TODO: add method to get sources from mcProvider (ie run forge 1 step further)
        provider.mcPatcher.createSourcesJar(provider.sourceSet.compileClasspath, mcDevFile, sourcesJar, linemappedJar, provider.side)
    }

}