package xyz.wagyourtail.unimined.internal.mods.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mod.task.GenModSourcesTask
import xyz.wagyourtail.unimined.internal.mods.ModRemapProvider
import xyz.wagyourtail.unimined.internal.mods.ModsProvider
import javax.inject.Inject

abstract class GenModSourcesTaskImpl @Inject constructor(@get:Internal val provider: ModsProvider) : GenModSourcesTask() {

    @TaskAction
    fun run() {
        for (modProvider in provider.remapConfigsResolved.values) {
            genSources(modProvider)
        }
    }

    fun genSources(mods: ModRemapProvider) {
        TODO()
    }

}