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
        target.extensions.create(
            "uniminedPublishModpublisher", ModpublisherExtension::class.java
        )
        // TODO: when the ModPublisher plugin is on the classpath, feed
        // ArtifactSelection.select(...) results into its upload task —
        // tracked in Docs/progress.md
    }

}
