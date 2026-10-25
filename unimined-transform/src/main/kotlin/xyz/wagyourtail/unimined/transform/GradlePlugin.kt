package xyz.wagyourtail.unimined.transform

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.internal.EnvironmentClasspathWiring

/**
 * Plugin id `xyz.wagyourtail.unimined.transform`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("transform", TransformExtension::class.java, target)

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
                minecraft.onEnvironmentBound { environment, sourceSet ->
                    EnvironmentClasspathWiring.wire(target, environment, sourceSet)
                }
            }
        }
    }

}
