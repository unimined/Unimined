package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.minecraft.task.PruneUniminedCacheTask

/**
 * Plugin id `xyz.wagyourtail.unimined.minecraft`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.mapping.GradlePlugin::class.java)
        target.extensions.create("minecraft", MinecraftExtension::class.java, target)

        target.tasks.register(
            "pruneUniminedCache", PruneUniminedCacheTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Prunes stale unimined caches (global by age, project dir with --project)"
            t.globalCacheDir.set(target.layout.dir(target.provider {
                UniminedCache.global(target).toFile()
            }))
            t.projectCacheDir.set(target.layout.dir(target.provider {
                UniminedCache.project(target).toFile()
            }))
            t.maxAgeDays.convention(
                target.providers.gradleProperty("unimined.pruneAgeDays")
                    .map { it.toInt() }.orElse(30)
            )
        }
    }

}
