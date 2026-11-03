package xyz.wagyourtail.unimined.remapper.tiny

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.remapper.tiny` — supplies the
 * `remap` step implementation backed by `net.fabricmc:tiny-remapper`.
 *
 * Step impls self-register via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-remap`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
    }

}
