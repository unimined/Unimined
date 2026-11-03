package xyz.wagyourtail.unimined.remap

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.remap` — remapper SPI host for
 * artifact & dependency jar remapping. Defines the `remap { }` DSL and
 * the step-pipeline machinery; engines are separate plugins.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("remap", RemapExtension::class.java, target)
    }

}
