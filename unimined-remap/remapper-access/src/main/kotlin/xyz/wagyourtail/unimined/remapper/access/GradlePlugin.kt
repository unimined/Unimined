package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.remap.stepVerb

/**
 * Plugin id `xyz.wagyourtail.unimined.remapper.access` — supplies the
 * `remap-access` step on binary surfaces, rewriting embedded AW/AT/CT
 * resources so non-UML remapper engines (notably tiny-remapper) produce
 * target-namespace access metadata.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        target.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java
        ).registerDslVerb("applyClassTweaker") { spec ->
            spec.stepVerb(ApplyClassTweakerStepSpec::class.java)
        }
    }

}
