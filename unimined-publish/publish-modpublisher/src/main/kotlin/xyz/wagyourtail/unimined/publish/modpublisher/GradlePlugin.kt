package xyz.wagyourtail.unimined.publish.modpublisher

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.publish-modpublisher` — routes
 * production artifacts to upload destinations via
 * [ArtifactSelection] rules: the loader-merge merged artifact wins
 * when present, else per-loader production jars; dev artifacts are
 * never selectable.
 *
 * The ModPublisher upload engine itself (`gg.hyperspace.modpublisher`
 * or firstdarkdev's plugin) is applied by the consuming build; this
 * adapter owns selection + credential plumbing only, per plan §11.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create(
            "uniminedPublishModpublisher", ModpublisherExtension::class.java
        )

        // mergedElements convention: when loader-merge produces a
        // `merged` artifact it becomes the upload artifact by default
        // (ArtifactSelection prefers merged over per-loader prod)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.mod-loader-merge") {
            val merged = target.configurations.findByName("mergedElements")
                ?.outgoing?.artifacts?.firstOrNull()?.file
            if (merged != null) {
                ext.mergedArtifact.convention(
                    target.layout.file(target.provider { merged })
                )
            }
        }

        // typed wiring lands only when the consuming build applies
        // ModPublisher — the adapter never applies it itself
        target.pluginManager.withPlugin("com.hypherionmc.modutils.modpublisher") {
            ModpublisherWiring.wire(target, ext)
        }
    }

}
