package xyz.wagyourtail.unimined.remap

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.uniminedAdopt

/**
 * Plugin id `xyz.wagyourtail.unimined.remap` — remapper SPI host for
 * artifact & dependency jar remapping. Defines the `remap { }` DSL and
 * the step-pipeline machinery; engines are separate plugins.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("uniminedRemap", RemapExtension::class.java, target)
        target.uniminedAdopt("remap", RemapExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("remap", RemapExtension::class.java)
        }
    }

}
