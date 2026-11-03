package xyz.wagyourtail.unimined.loadermerge

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import xyz.wagyourtail.unimined.loadermerge.task.MergeLoaderJarsTask

/**
 * `xyz.wagyourtail.unimined.mod-loader-merge` — host for optional
 * loader-jar merging. Declares `loaderMerge { }`, wires
 * `mergeLoaderJars`, and publishes the merged jar on a resolvable
 * `mergedElements` configuration so publishers can select it.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "loaderMerge", LoaderMergeExtension::class.java
        )

        val merged = project.configurations.register("mergedElements") {
            it.description = "merged loader-jar artifacts"
            it.isCanBeConsumed = true
            it.isCanBeResolved = false
            it.attributes { a ->
                a.attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME)
                )
            }
        }

        val mergeTask = project.tasks.register(
            "mergeLoaderJars", MergeLoaderJarsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Merges per-loader mod jars into a single artifact"
            t.engine.set(ext.engine)
            t.outputJar.set(
                project.layout.buildDirectory.file("libs/${project.name}-merged.jar")
            )
            t.inputNames.set(project.provider { ext.inputsInternal.keys.toList() })
            t.loaderJars.from(project.provider {
                ext.inputsInternal.values.flatMap { it() }
            })
        }
        merged.configure {
            it.outgoing.artifact(
                mergeTask.flatMap(MergeLoaderJarsTask::outputJar)
            ) { a -> a.classifier = "merged" }
        }
    }

}
