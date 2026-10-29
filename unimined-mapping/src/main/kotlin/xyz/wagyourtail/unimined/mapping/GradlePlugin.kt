package xyz.wagyourtail.unimined.mapping

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.UniminedCache

/**
 * Plugin id `xyz.wagyourtail.unimined.mapping` — mapping SPI host for
 * source materialization, provider selection, composition, and serialized
 * mapping bundle tasks.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.extensions.create("mapping", MappingExtension::class.java)
        extension.cacheDir.convention(
            target.layout.projectDirectory.dir(".gradle/unimined/mapping-cache")
        )
        extension.globalCacheDir.convention(
            target.layout.dir(target.provider {
                UniminedCache.global(target).resolve("mapping-cache").toFile()
            })
        )
    }

}
