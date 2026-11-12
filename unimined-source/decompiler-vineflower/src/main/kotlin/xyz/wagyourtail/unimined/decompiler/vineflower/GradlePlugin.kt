package xyz.wagyourtail.unimined.decompiler.vineflower

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.Perf

/**
 * Plugin id `xyz.wagyourtail.unimined.decompiler.vineflower` — supplies
 * the `genSources` engine backed by `org.vineflower:vineflower`.
 *
 * The engine self-registers via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-source`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        Perf.syncFrom(target)
        target.pluginManager.apply(xyz.wagyourtail.unimined.source.GradlePlugin::class.java)
    }

}
