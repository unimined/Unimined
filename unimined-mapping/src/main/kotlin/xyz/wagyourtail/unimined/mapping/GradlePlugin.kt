package xyz.wagyourtail.unimined.mapping

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.mapping` — mapping SPI host for
 * source materialization, provider selection, composition, and serialized
 * mapping bundle tasks.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("mapping", MappingExtension::class.java)
    }

}
