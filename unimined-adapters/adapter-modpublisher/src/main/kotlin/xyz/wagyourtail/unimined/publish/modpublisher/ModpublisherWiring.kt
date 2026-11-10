package xyz.wagyourtail.unimined.publish.modpublisher

import com.hypherionmc.modpublisher.plugin.ModPublisherGradleExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import java.io.File

/**
 * Conventions Unimined-derived values onto ModPublisher's own
 * `publisher` extension — no adapter DSL. Every assignment is a
 * `convention`, so anything the user sets in `publisher { }` wins.
 *
 * Invoked under `withPlugin("com.hypherionmc.modutils.modpublisher")`
 * — the upstream classes only exist when the consuming build applies
 * ModPublisher (compileOnly dependency).
 */
internal object ModpublisherWiring {

    fun wire(
        project: Project,
        bound: List<Pair<MinecraftEnvironment, SourceSet>>,
        mergedArtifact: Provider<File>?
    ) {
        val publisher = project.extensions.getByName("publisher")
                as ModPublisherGradleExtension

        // env id → prod jar: remap<JarTask> when the env remaps, the
        // env's plain jar task otherwise. Resolved lazily — prod jar
        // tasks register late
        val perLoader = project.provider {
            bound.filter { !it.first.isAuxEnv.getOrElse(false) }
                .associate { (env, sourceSet) ->
                    val remapName = "remap" + sourceSet.jarTaskName
                        .replaceFirstChar(Char::uppercase)
                    val jarTask = project.tasks.findByName(remapName)
                        ?: project.tasks.findByName(sourceSet.jarTaskName)
                    env.environmentId to
                            ((jarTask as? org.gradle.jvm.tasks.Jar)
                                ?.archiveFile?.get()?.asFile
                                ?: throw org.gradle.api.GradleException(
                                    "unimined-modpublisher: no prod jar " +
                                            "task for env '${env.environmentId}'"))
                }
        }
        val selected = project.provider {
            ArtifactSelection.select(
                mergedArtifact?.orNull,
                perLoader.get()
            )
        }

        publisher.artifact.convention(selected.map { it.firstOrNull() })
        publisher.additionalFiles.convention(selected.map { sel ->
            sel.drop(1).map { f ->
                project.objects.newInstance(
                    ModPublisherGradleExtension.AdditionalFile::class.java
                ).also { it.artifact(f) }
            }
        })

        publisher.loaders.convention(project.provider {
            bound.filter { !it.first.isAuxEnv.getOrElse(false) }
                .mapNotNull { it.first.loaderSpec.orNull?.name }
                .distinct()
        })
        publisher.gameVersions.convention(project.provider {
            bound.filter { !it.first.isAuxEnv.getOrElse(false) }
                .mapNotNull { it.first.version.orNull }
                .distinct()
        })
        // upstream `version` is deprecated in favor of `projectVersion`,
        // whose own convention chains to `version` — setting ours on
        // `version` preserves the full override precedence either way
        @Suppress("DEPRECATION")
        publisher.version.convention(
            project.provider { project.version.toString() })
    }

}
