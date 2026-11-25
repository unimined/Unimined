package xyz.wagyourtail.unimined.transform.internal.wiring

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.util.MavenCoords
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.UniminedProblems
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.internal.steps.NestedJarsAppender
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.remap.task.RemapJarTask
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

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
 * The swap happens post-DSL (via [afterDslEval]): the user's
 * `archiveClassifier`/`defaultRemapJar` settings must be final before
 * the suffix is appended and the output name captured. Deferring
 * within configuration keeps configuration-cache compatibility — at
 * the documented cost of eagerly realizing the jar task.
 */
internal object ProductionJarWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        nestedJars: NestedJarsPlan? = null
    ) {
        project.afterDslEval {
            wireProductionJar(project, environment, sourceSet, nestedJars)
        }
    }

    private fun wireProductionJar(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        nestedJars: NestedJarsPlan?
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
        val prodNamespace = prodNamespace(project, minecraft, environment)

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
            t.remapClasspath.from(splitClientClasspath(project, environment))
            t.footgunChecks.set(remap.footgunChecks)
            nestedJars?.let { p ->
                t.nestedJars.from(p.files)
                t.nestedJarEntries.set(p.entries)
                t.nestedJarsFormat.set(p.format)
                t.nestedJarsRecompress.set(p.recompress)
                t.nestedJarsMetadataFile.set(p.metadataFile)
                t.nestedJarsKey.set(p.nestedJarsKey)
            }
        }
        // prod-mapped sources — 1.x's `remapSourcesJar`: a conventional
        // `sourcesJar` carries dev-namespace sources under a `-dev`
        // classifier; the remap task mirrors its spec through the
        // SOURCES pipeline and emits the prod-named `-sources` artifact.
        // `prodBundle` is reused — the dev->prod tree is identical to
        // the binary remap's; only the pipeline surface differs
        // env-scoped name (main -> sourcesJar, fabric -> fabricSourcesJar)
        // — the same per-env remap naming as remap${jarTaskName}
        val sourcesJarTaskName = sourceSet.sourcesJarTaskName
        val sourcesJar = (project.tasks.findByName(sourcesJarTaskName) as? Jar)
            ?: project.tasks.register(sourcesJarTaskName, Jar::class.java) { t ->
                t.description = "Assembles the '${sourceSet.name}' sources jar"
                t.group = "build"
                t.from(sourceSet.allSource)
                t.archiveClassifier.set("sources")
                // env-qualified name like wireEnvJar's — without it
                // every env's sources jar (and its remap) emits
                // `<name>-<version>-sources.jar`, colliding with
                // main's in build/libs
                if (environmentId != "main") {
                    t.archiveAppendix.set(environmentId)
                }
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
            "remap${sourcesJarTaskName.cap()}",
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
            t.remapClasspath.from(splitClientClasspath(project, environment))
            t.footgunChecks.set(remap.footgunChecks)
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
        // identity environments collapse remapJar — the plain jar IS the
        // production artifact, so the JiJ post-pass must land here
        // instead of in the (skipped) JAR pipeline. The doLast reads the
        // resolved input values off task.inputs — no provider/config
        // captures in the serialized action (config-cache safe).
        nestedJars?.let { p ->
            jar.inputs.files(p.files)
            jar.inputs.property(
                "nestedJarPaths",
                p.entries.map { l ->
                    l.map { it.jar.get().asFile.toPath().toString() }
                }
            )
            jar.inputs.property(
                "nestedJarCoords",
                p.entries.map { l -> l.map { it.coords.orNull } }
            )
            jar.inputs.property(
                "nestedJarRanges",
                p.entries.map { l -> l.map { it.versionRange.orNull } }
            )
            jar.inputs.property(
                "nestedJarTypes",
                p.entries.map { l -> l.map { it.fmlModType.orNull } }
            )
            jar.inputs.property(
                "nestedJarObfuscated",
                p.entries.map { l -> l.map { it.obfuscated.orNull } }
            )
            // nullable providers need a sentinel — registered inputs must
            // always resolve; "" = absent (doLast treats it as unset)
            jar.inputs.property("nestedJarsFormat", p.format.orElse(""))
            jar.inputs.property("nestedJarsRecompress", p.recompress)
            jar.inputs.property("nestedJarsMetadataFile", p.metadataFile.orElse(""))
            jar.inputs.property("nestedJarsKey", p.nestedJarsKey.orElse(""))
            jar.inputs.property("nestedJarsExpectedNamespace", prodNamespace)
            jar.inputs.property("nestedJarsIdentity", identity)
            // `objects` rides the service-reference codec into the
            // serialized action — a `project` capture would serialize
            // DefaultProject (config-cache violation); reporting goes
            // through UniminedProblems on the task's own logger
            val objects = project.objects
            jar.doLast { task ->
                val props = task.inputs.properties
                if (props["nestedJarsIdentity"] != true) return@doLast
                val format = (props["nestedJarsFormat"] as? String)
                    ?.takeUnless { it.isEmpty() }
                    ?: return@doLast
                @Suppress("UNCHECKED_CAST")
                val rows = NestedJarsAppender.append(
                    (task as Jar).archiveFile.get().asFile.toPath(),
                    (props["nestedJarPaths"] as List<String>).mapIndexed { i, s ->
                        NestedJarsAppender.Input(
                            java.nio.file.Path.of(s),
                            (props["nestedJarCoords"] as List<String?>)
                                .getOrNull(i)?.let(::MavenCoords),
                            (props["nestedJarRanges"] as List<String?>)
                                .getOrNull(i),
                            (props["nestedJarTypes"] as List<String?>)
                                .getOrNull(i),
                            (props["nestedJarObfuscated"] as List<Boolean?>)
                                .getOrNull(i)
                        )
                    },
                    format,
                    props["nestedJarsRecompress"] as? Boolean ?: true,
                    (props["nestedJarsMetadataFile"] as? String)
                        ?.takeUnless { it.isEmpty() },
                    (props["nestedJarsKey"] as? String)
                        ?.takeUnless { it.isEmpty() },
                    props["nestedJarsExpectedNamespace"] as? String
                )
                rows.forEach {
                    UniminedProblems.report(
                        task.logger, objects, "nested-jars", it)
                }
            }
        }
        project.tasks.findByName("build")?.dependsOn(remapJar, remapSourcesJar)
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

    /**
     * The split client env's compile classpath for a
     * `splitEnvironmentSourceSets` parent — loom wires
     * `clientOnlyCompileClasspath` onto the remap jar: the packaged
     * jar carries client-only classes, so name resolution needs the
     * full client view (client jar + common), not just `main`'s
     * intersection. Empty for unsplit envs.
     */
    private fun splitClientClasspath(
        project: Project,
        environment: MinecraftEnvironment
    ): Provider<FileCollection> = project.provider {
        val clientEnvId = environment.splitClientEnvironment.orNull
            ?: return@provider project.files()
        project.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .findByName(clientEnvId)
            ?.let { project.files(it.compileClasspath) }
            ?: project.files()
    }

}

/** The environment's production namespace — the single entry of the
 *  env's own `modSourceNamespaces` when a loader family or build script
 *  populated it, or for multi-source envs (bukkit: "spigot" +
 *  "spigotProd") the loader's declared `prodNamespace` when it is one
 *  of those sources (1.x `defaultProdNamespace`). When the env property
 *  is empty, the descriptor's `prodNamespace` is authoritative: the
 *  `loaderModSourceNamespaces` fallback intentionally describes where
 *  dependency artifacts start, not where this env's own output must end
 *  (LiteLoader deps are searge binaries while the runtime litemod is
 *  official/notch). Loaderless envs fall back to the mapping-derived
 *  source list. Shared by the prod jar remap and the `minecraftProd`
 *  MC view so both agree on what "prod" means for the environment. */
internal fun prodNamespace(
    project: Project,
    minecraft: MinecraftExtension,
    environment: MinecraftEnvironment,
): Provider<String> {
    val environmentId = environment.environmentId
    return project.provider {
        val descriptor = environment.loaderSpec.orNull
            ?.let { LoaderRegistry.resolveBuiltin(it.name) }
        fun select(namespaces: List<String>): String =
            if (namespaces.size == 1) namespaces.single() else {
                descriptor?.prodNamespace
                    ?.takeIf { it in namespaces }
                    ?: project.failProblem(
                        "env/mod-source-namespaces",
                        "unimined: environment '$environmentId' declares " +
                                "${namespaces.size} mod source namespaces",
                        "default production namespace needs exactly one; " +
                                "use remap.remappedViews(...) for mixed inputs"
                    )
            }
        val declared = environment.modSourceNamespaces.getOrElse(emptyList())
        when {
            declared.isNotEmpty() -> select(declared)
            // A known loader's runtime contract is authoritative; checking
            // the MC metadata first would force a manifest lookup even for
            // loaders whose prod namespace is version-independent.
            descriptor != null -> descriptor.prodNamespace
            !environment.obfuscated.get() -> "mojmap"
            else -> select(minecraft.modSourceNamespaces(environmentId).get())
        }
    }
}
