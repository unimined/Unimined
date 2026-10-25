package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.minecraft`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.mapping.GradlePlugin::class.java)
        target.extensions.create("minecraft", MinecraftExtension::class.java, target)
    }

}
