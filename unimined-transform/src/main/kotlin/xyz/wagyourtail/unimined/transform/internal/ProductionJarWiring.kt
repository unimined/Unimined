package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
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
 * Environments whose production namespace equals the dev namespace
 * ([MinecraftExtension.isIdentityRemap]) collapse instead: the jar
 * keeps its classifier and `remap<JarTask>` skips itself, so a single
 * artifact serves development and production (plan: no duplicate
 * artifacts for a nonexistent namespace remap). Both decisions are
 * lazy providers — namespace resolution never runs at configuration
 * time.
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

        // identity environments (prod == dev namespace) emit one jar
        // usable for both development and production — the classifier
        // swap and the remap itself are skipped so no duplicate
        // artifacts represent a nonexistent namespace hop. The decision
        // stays lazy so unimined never resolves mappings at
        // configuration time.
        val identity = minecraft.isIdentityRemap(environmentId)

        val originalClassifier = jar.archiveClassifier.getOrElse("")
        val remapJar = project.tasks.register(
            "remap${jarTaskName.cap()}",
            RemapJarTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Assembles and remaps the '$jarTaskName' contents to the production namespace"
            t.onlyIf("the production namespace differs from the dev namespace") {
                !identity.get()
            }
            // mirror the jar's copy spec + manifest — resolves its `from`s
            // at copy time, so remapJar depends on the jar's *inputs*
            // (classes/resources), never on the jar task's output
            t.rootSpec.with(jar.rootSpec)
            // the mirrored spec carries the jar's MANIFEST.MF and this
            // task's own Jar machinery emits one too — `with()`-wrapped
            // specs bypass parent excludes, so first-writer-wins is the
            // only lever; both candidates carry the user's attrs anyway
            t.manifest.from(jar.manifest)
            t.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            // emit the artifact the jar *would* have produced before the
            // `-dev` classifier swap — name parts flow as providers
            t.archiveBaseName.set(jar.archiveBaseName)
            t.archiveAppendix.set(jar.archiveAppendix)
            t.archiveVersion.set(jar.archiveVersion)
            t.archiveExtension.set(jar.archiveExtension)
            t.archiveClassifier.set(originalClassifier)
            t.destinationDirectory.set(jar.destinationDirectory)
            t.sourceNamespace.set(devNamespace)
            t.targetNamespace.set(prodNamespace)
            t.surface.set(RemapSurface.JAR)
            t.mappingFile.set(prodBundle)
            t.stepSpecs.set(prodSteps)
            // environment-declared access-model contributions — the
            // analysis overlay for the production remap (a mod's own
            // widened members must propagate)
            t.accessModel.set(project.provider {
                AccessModel.merge(
                    remap.sourceSet(environmentId)
                        .accessModelProviders.map { it.get() }
                ).serialize()
            })
            // the dev classpath: remapped mod views + dev MC + libraries —
            // feeds inheritance/propagation for engines that need it
            t.remapClasspath.from(sourceSet.compileClasspath)
        }
        // prod-mapped sources — 1.x's `remapSourcesJar`: a conventional
        // `sourcesJar` carries dev-namespace sources under a `-dev`
        // classifier; the remap task mirrors its spec through the
        // SOURCES pipeline and emits the prod-named `-sources` artifact.
        // `prodBundle` is reused — the dev->prod tree is identical to
        // the binary remap's; only the pipeline surface differs
        val sourcesJar = (project.tasks.findByName("sourcesJar") as? Jar)
            ?: project.tasks.register("sourcesJar", Jar::class.java) { t ->
                t.description = "Assembles the '${sourceSet.name}' sources jar"
                t.group = "build"
                t.from(sourceSet.allSource)
                t.archiveClassifier.set("sources")
                t.destinationDirectory.set(jar.destinationDirectory)
            }.get()

        // environments contribute no SOURCES-surface steps by default,
        // so prepend the implicit remap core — on SOURCES it binds the
        // source remapper (decl dev->prod names in .java files)
        val prodSourcesSteps = remap.pipelineSteps(
            environmentId, RemapSurface.SOURCES
        ).map { steps ->
            val filtered = steps.filter { it !is DevArtifactOnlyStepSpec }
            if (filtered.none { it is RemapStepSpec }) {
                listOf<StepSpec>(RemapStepSpec()) + filtered
            } else filtered
        }

        val originalSourcesClassifier = sourcesJar.archiveClassifier.getOrElse("sources")
        val remapSourcesJar = project.tasks.register(
            "remapSourcesJar",
            RemapJarTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Assembles and remaps the 'sourcesJar' contents " +
                    "to the production namespace"
            t.onlyIf("the production namespace differs from the dev namespace") {
                !identity.get()
            }
            t.rootSpec.with(sourcesJar.rootSpec)
            t.manifest.from(sourcesJar.manifest)
            t.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            t.archiveBaseName.set(sourcesJar.archiveBaseName)
            t.archiveAppendix.set(sourcesJar.archiveAppendix)
            t.archiveVersion.set(sourcesJar.archiveVersion)
            t.archiveExtension.set(sourcesJar.archiveExtension)
            t.archiveClassifier.set(originalSourcesClassifier)
            t.destinationDirectory.set(sourcesJar.destinationDirectory)
            t.surface.set(RemapSurface.SOURCES)
            t.sourceNamespace.set(devNamespace)
            t.targetNamespace.set(prodNamespace)
            t.mappingFile.set(prodBundle)
            t.stepSpecs.set(prodSourcesSteps)
            t.accessModel.set(project.provider {
                AccessModel.merge(
                    remap.sourceSet(environmentId)
                        .accessModelProviders.map { it.get() }
                ).serialize()
            })
            t.remapClasspath.from(sourceSet.compileClasspath)
        }
        sourcesJar.archiveClassifier.set(
            identity.map { isIdentity ->
                if (isIdentity) originalSourcesClassifier
                else "$originalSourcesClassifier-dev"
            }
        )

        jar.archiveClassifier.set(
            identity.map { isIdentity ->
                if (isIdentity) originalClassifier
                else if (originalClassifier.isEmpty()) "dev"
                else "$originalClassifier-dev"
            }
        )
        project.tasks.findByName("build")?.dependsOn(remapJar, remapSourcesJar)
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

}
