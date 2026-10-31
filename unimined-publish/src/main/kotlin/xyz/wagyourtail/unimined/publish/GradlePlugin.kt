package xyz.wagyourtail.unimined.publish

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.publish.internal.PublishWiring

/**
 * Plugin id `xyz.wagyourtail.unimined.publish` — publisher host.
 *
 * Creates one `AdhocComponentWithVariants` per bound source-set/loader
 * environment (populated from that env's outgoing API/runtime/source
 * configurations) and exposes `uniminedPublish { }`. Destination
 * adapters (`unimined-publish-maven`, `-modpublisher`) plug in via the
 * publish SPI; this host never creates a `java` publication unless
 * `uniminedPublish { javaPublication() }` opts in.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create(
            "uniminedPublish", PublishExtension::class.java
        )
        ext.javaPublication.convention(false)

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, sourceSet ->
                PublishWiring.wire(target, environment, sourceSet, ext)
            }
        }
    }

}
