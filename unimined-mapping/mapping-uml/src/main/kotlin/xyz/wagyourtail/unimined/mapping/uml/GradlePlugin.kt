package xyz.wagyourtail.unimined.mapping.uml

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.mapping.uml` — supplies the default
 * UML-backed mapping provider.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.mapping.GradlePlugin::class.java)
    }

}
