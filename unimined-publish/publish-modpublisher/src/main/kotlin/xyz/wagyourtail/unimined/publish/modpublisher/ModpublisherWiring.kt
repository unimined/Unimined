package xyz.wagyourtail.unimined.publish.modpublisher

import com.hypherionmc.modpublisher.plugin.ModPublisherGradleExtension
import org.gradle.api.Project
import java.io.File

/**
 * Feeds `uniminedPublishModpublisher { }` + the
 * [ArtifactSelection] result into ModPublisher's `publisher`
 * extension. Invoked under `withPlugin("com.hypherionmc
 * .modutils.modpublisher")` — the upstream classes only exist when
 * the consuming build applies ModPublisher (compileOnly dependency).
 */
internal object ModpublisherWiring {

    fun wire(project: Project, ext: ModpublisherExtension) {
        // deferred: artifact selection must see the final merged/
        // per-loader providers, and upload config lands before
        // publishMod executes
        project.afterEvaluate {
            val publisher = project.extensions.getByName("publisher")
                as ModPublisherGradleExtension

            val merged = ext.mergedArtifact.orNull?.asFile
            val prod = ext.perLoaderArtifacts.get()
                .mapValues { (_, f) -> f.asFile }
            val selected = ArtifactSelection.select(merged, prod)

            // ModPublisher accepts File/Task/String/provider artifacts;
            // first selected file is the primary, rest are additional
            selected.firstOrNull()?.let { publisher.artifact.set(it) }
            selected.drop(1).forEach { publisher.addAdditionalFile(it) }
            ext.additionalFiles.get().forEach {
                publisher.addAdditionalFile(it.asFile)
            }

            publisher.apiKeys { keys ->
                ext.apiKeys.curseforge.orNull?.let { keys.curseforge(it) }
                ext.apiKeys.modrinth.orNull?.let { keys.modrinth(it) }
                ext.apiKeys.github.orNull?.let { keys.github(it) }
            }

            ext.curseID.orNull?.let { publisher.curseID.set(it) }
            ext.modrinthID.orNull?.let { publisher.modrinthID.set(it) }
            ext.githubRepo.orNull?.let { publisher.githubRepo.set(it) }
            ext.version.orNull?.let { publisher.version.set(it) }
            ext.versionType.orNull?.let { publisher.versionType.set(it) }
            ext.changelog.orNull?.let { publisher.changelog.set(it) }
            ext.displayName.orNull?.let { publisher.displayName.set(it) }
            ext.curseEnvironment.orNull?.let {
                publisher.setCurseEnvironment(it)
            }
            if (ext.gameVersions.get().isNotEmpty()) {
                publisher.setGameVersions(*ext.gameVersions.get().toTypedArray())
            }
            if (ext.loaders.get().isNotEmpty()) {
                publisher.setLoaders(*ext.loaders.get().toTypedArray())
            }
            ext.debug.orNull?.let { publisher.debug.set(it) }
        }
    }

}
