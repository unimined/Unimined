package xyz.wagyourtail.unimined.transform

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.transform`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("transform", TransformExtension::class.java, target)
    }

}
