package xyz.wagyourtail.unimined.decompiler.vineflower

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.decompiler.vineflower` — supplies
 * the `genSources` engine backed by `org.vineflower:vineflower`.
 *
 * The engine self-registers via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-source`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.source.GradlePlugin::class.java)
    }

}
