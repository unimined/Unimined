package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.remapper.uml` — supplies `remap` (and
 * later `unpick`/enrichment) step implementations backed by
 * `UniminedMappingLibrary`.
 *
 * Step impls self-register via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-remap`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
    }

}
