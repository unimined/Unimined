package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.task.RemapJarTask

/**
 * Production-artifact wiring for one bound environment + source set —
 * the `defaultRemapJar`/`remap<JarTask>` convention from 1.x and loom:
 *
 * - the source set's `Jar` task output gains a `-dev` classifier
 *   (it stays in the dev namespace),
 * - a `remap<JarTask>` ([RemapJarTask]) re-runs the environment's JAR
 *   pipeline minus [DevArtifactOnlyStepSpec]s — `dev -> mod-source`
 *   namespace, e.g. `named -> intermediary` for fabric — and emits the
 *   artifact under the jar's *original* classifier,
 * - `build` depends on the remap task so `build/libs` carries both.
 *
 * The swap happens in [Project.afterEvaluate]: the user's
 * `archiveClassifier`/`defaultRemapJar` settings must be final before
 * the suffix is appended and the output name captured. `afterEvaluate`
 * runs during configuration, so configuration-cache compatibility is
 * preserved — at the documented cost of eagerly realizing the jar task.
 */
internal object ProductionJarWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        project.afterEvaluate {
            wireProductionJar(project, environment, sourceSet)
        }
    }

    private fun wireProductionJar(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        if (!environment.defaultRemapJar.get()) return
        val jarTaskName = sourceSet.jarTaskName
        val jar = project.tasks.findByName(jarTaskName) as? Jar ?: run {
            project.logger.info(
                "[Unimined] source set '${sourceSet.name}' has no jar task " +
                    "'$jarTaskName' — skipping default production remap; " +
                    "register one or disable `defaultRemapJar`"
            )
            return
        }

        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentId = environment.environmentId

        val devNamespace = minecraft.devNamespace(environmentId)
        val prodNamespace = minecraft.modSourceNamespaces(environmentId).map { namespaces ->
            require(namespaces.size == 1) {
                "environment '$environmentId' declares ${namespaces.size} mod " +
                    "source namespaces — default production remap needs exactly " +
                    "one; use remap.remappedViews(...) for mixed inputs"
            }
            namespaces.single()
        }

        // task-produced bundle (producer task dep flows through
        // mappingFile) — unlike transform params, tasks are in the graph
        val prodBundle = minecraft.mappingBundle(
            environmentId, "prod-$jarTaskName", devNamespace, prodNamespace
        )

        // the env JAR pipeline minus steps that only make sense on the
        // Minecraft dev artifact (e.g. applying collected AWs to MC)
        val prodSteps = remap.pipelineSteps(environmentId, RemapSurface.JAR).map { steps ->
            steps.filter { it !is DevArtifactOnlyStepSpec }
        }

        val originalClassifier = jar.archiveClassifier.getOrElse("")
        val remapJar = project.tasks.register(
            "remap${jarTaskName.cap()}",
            RemapJarTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Remaps the '$jarTaskName' output jar to the production namespace"
            t.inputArtifact.set(jar.archiveFile)
            t.sourceNamespace.set(devNamespace)
            t.targetNamespace.set(prodNamespace)
            t.mappingFile.set(prodBundle)
            t.stepSpecs.set(prodSteps)
            // the dev classpath: remapped mod views + dev MC + libraries —
            // feeds inheritance/propagation for engines that need it
            t.remapClasspath.from(sourceSet.compileClasspath)
            t.outputArtifact.set(
                project.layout.buildDirectory.file(
                    "libs/${archiveFileName(jar, originalClassifier)}"
                )
            )
        }
        jar.archiveClassifier.set(
            if (originalClassifier.isEmpty()) "dev" else "$originalClassifier-dev"
        )
        project.tasks.findByName("build")?.dependsOn(remapJar)
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

    /**
     * Reproduce [Jar]'s archive file naming
     * (`base[-appendix][-version][-classifier].extension`) with a
     * substituted classifier — the remap task emits the artifact the jar
     * *would* have produced before the `-dev` suffix.
     */
    private fun archiveFileName(jar: Jar, classifier: String): String {
        val parts = listOf(
            jar.archiveBaseName.getOrElse(""),
            jar.archiveAppendix.getOrElse(""),
            jar.archiveVersion.getOrElse(""),
            classifier
        ).filter { it.isNotEmpty() }
        return parts.joinToString("-") + "." + jar.archiveExtension.getOrElse("jar")
    }

}
