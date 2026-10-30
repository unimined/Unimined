package xyz.wagyourtail.unimined.remapper.source

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.remap.stepVerb

/**
 * Plugin id `xyz.wagyourtail.unimined.remapper.source` — supplies the
 * `remap` step on the sources surface, backed by the
 * `xyz.wagyourtail.unimined:source-remap` (ReplayMod-fork) tool.
 *
 * Step impls self-register via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-remap`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        target.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java
        ).registerDslVerb("sourceRemap") { spec ->
            spec.stepVerb(SourceRemapStepSpec::class.java)
        }
    }

}
