package xyz.wagyourtail.unimined.loadermerge

import io.github.pacifistmc.forgix.plugin.configurations.ForgixConfiguration
import io.github.pacifistmc.forgix.utils.GradleProjectUtils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.loadermerge.task.MergeLoaderJarsTask
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.io.File

/**
 * `xyz.wagyourtail.unimined.adapter-forgix` — merges per-loader
 * production jars with Forgix's engine and publishes the result on a
 * consumable `mergedElements` configuration (classifier `merged`) so
 * publishers can select it.
 *
 * Forgix's own Gradle plugin only supports multi-project layouts —
 * `forgix.merge(name)` rejects names that aren't subprojects, and its
 * `mergeJars` task null-checks the project before consulting
 * `inputJar`, so flat env-per-source-set projects can't use it. This
 * adapter instead drives the layout-agnostic `Forgix.mergeLoaders`
 * engine from its own `mergeLoaderJars` task.
 *
 * Merge inputs, in increasing precedence: bound non-aux environments'
 * prod jars (`remap<Env>Jar`/`jar`, keyed by `loaderSpec.name` → env
 * id); `forgix { }` `mergeConfigurations` when Forgix's plugin is
 * applied (resolved through its own `GradleProjectUtils`); explicit
 * `loaderMerge.from(...)` entries.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "loaderMerge", LoaderMergeExtension::class.java
        )

        // env-bound auto-feed — collected during configuration, read
        // lazily inside the task input providers below so explicit
        // `from(...)` declarations (which may come later in the build
        // script) are already visible
        val discovered = linkedMapOf<String, Provider<Task>>()
        project.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = project.extensions.getByType(
                MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { env, sourceSet ->
                if (env.isAuxEnv.getOrElse(false)) return@onEnvironmentBound
                val jar = project.provider<Task> {
                    val remapName = "remap" + sourceSet.jarTaskName
                        .replaceFirstChar(Char::uppercase)
                    project.tasks.findByName(remapName)
                        ?: project.tasks.findByName(sourceSet.jarTaskName)
                        ?: throw GradleException(
                            "adapter-forgix: no prod jar task " +
                                    "for env '${env.environmentId}'")
                }
                val name = env.loaderSpec.orNull?.name ?: env.environmentId
                // two envs sharing a loader name would collide on the
                // forgix loader label — disambiguate by env id
                if (discovered.putIfAbsent(name, jar) != null) {
                    discovered["$name-${env.environmentId}"] = jar
                }
            }
        }

        // forgix DSL configs — when the consuming build applies
        // io.github.pacifistmc.forgix, its merge() entries feed in too
        // (resolved through upstream's own heuristic so subproject
        // layouts behave identically)
        var forgixEntries: Provider<Map<String, File>>? = null
        project.pluginManager.withPlugin("io.github.pacifistmc.forgix") {
            val forgix = project.rootProject.extensions
                .getByType(ForgixConfiguration::class.java)
            forgixEntries = project.provider {
                forgix.mergeConfigurations.mapNotNull { (name, config) ->
                    // upstream's getBestOutputFile null-checks the
                    // project before consulting inputJar — replicate the
                    // intended order (explicit inputJar wins, subproject
                    // heuristic is the fallback) so flat projects work
                    val match = project.rootProject.allprojects
                        .find { it.name.equals(name, ignoreCase = true) }
                    (config.inputJar.orNull?.asFile
                        ?: match?.let {
                            GradleProjectUtils.getBestOutputFile(config, it)
                        })?.let { name to it }
                }.toMap()
            }
        }

        project.configurations.register("mergedElements") {
            it.description = "merged loader-jar artifacts"
            it.isCanBeConsumed = true
            it.isCanBeResolved = false
            it.attributes { a ->
                a.attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME)
                )
            }
        }.configure {
            it.outgoing.artifact(
                project.tasks.named(
                    "mergeLoaderJars", MergeLoaderJarsTask::class.java
                ).flatMap(MergeLoaderJarsTask::outputJar)
            ) { a -> a.classifier = "merged" }
        }

        project.tasks.register(
            "mergeLoaderJars", MergeLoaderJarsTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Merges per-loader mod jars into a single artifact"
            t.outputJar.set(
                project.layout.buildDirectory.file("libs/${project.name}-merged.jar")
            )
            // union in precedence order; both providers resolve the
            // same key set so names/jars stay parallel. later sources
            // override on the same key
            t.inputNames.set(project.provider {
                (discovered.keys +
                        (forgixEntries?.get()?.keys ?: emptySet()) +
                        ext.inputsInternal.keys).toList()
            })
            // route each declared source through project.files so
            // task-output providers carry their producer task as a
            // builtBy on the collection — plain File evaluation would
            // leave mergeLoaderJars running before its inputs exist
            t.loaderJars.from(project.provider {
                val forgix = forgixEntries?.get() ?: emptyMap()
                (discovered.keys + forgix.keys + ext.inputsInternal.keys)
                    .map { name ->
                        project.files(
                            ext.inputsInternal[name]
                                ?: forgix[name]
                                ?: discovered.getValue(name)
                        )
                    }
            })
        }
    }

}
