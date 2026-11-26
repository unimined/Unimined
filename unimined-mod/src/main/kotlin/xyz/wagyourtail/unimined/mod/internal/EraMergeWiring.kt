package xyz.wagyourtail.unimined.mod.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.AdditionalNestedJarInput
import xyz.wagyourtail.unimined.mod.EraInputSpec
import xyz.wagyourtail.unimined.mod.EraJarInput
import xyz.wagyourtail.unimined.mod.EraMergeSpec
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.mod.task.MergeEraJarsTask
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.io.File

/**
 * Project wiring for `mergedEraJar`/`chimeraJar`. Environment facts are
 * collected at bind time; the task-input snapshot itself stays behind
 * execution-time providers so namespace and artifact selection never resolve
 * mappings during configuration.
 */
internal object EraMergeWiring {

    internal enum class Flavor(
        val taskName: String,
        val classifier: String,
        val description: String
    ) {
        MERGED_ERA(
            "mergedEraJar", "merged",
            "Merges same-loader era production jars into one multi-version artifact"
        ),
        CHIMERA(
            "chimeraJar", "chimera",
            "Merges multi-loader era production jars into one chimera artifact"
        )
    }

    internal data class BoundEnvironment(
        val environment: MinecraftEnvironment,
        val jarTaskName: String
    )

    internal data class PlannedEra(
        val name: String,
        val environment: BoundEnvironment?,
        val override: EraInputSpec?
    )

    fun install(project: Project) {
        for (flavor in Flavor.entries) {
            val task = project.tasks.register(flavor.taskName, MergeEraJarsTask::class.java) { t ->
                t.group = "unimined"
                t.description = flavor.description
                t.archiveBaseName.set(project.provider {
                    project.extensions.findByType(BasePluginExtension::class.java)
                        ?.archivesName?.get() ?: project.name
                })
                t.archiveVersion.set(project.provider { project.version.toString() })
                t.archiveClassifier.set(flavor.classifier)
                t.archiveFileName.set(project.provider {
                    val parts = listOfNotNull(
                        t.archiveBaseName.orNull?.takeIf { it.isNotEmpty() },
                        t.archiveAppendix.orNull?.takeIf { it.isNotEmpty() },
                        t.archiveVersion.orNull?.takeIf { it.isNotEmpty() },
                        t.archiveClassifier.orNull?.takeIf { it.isNotEmpty() }
                    )
                    parts.joinToString("-") + "." + t.archiveExtension.get()
                })
                t.destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
            }
            project.afterDslEval {
                wire(project, flavor, task)
            }
        }
    }

    fun environmentBound(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        val mod = project.extensions.getByType(ModExtension::class.java)
        if (mod.eraMergeEnvironments.none {
                it.environment.environmentId == environment.environmentId
            }) {
            mod.eraMergeEnvironments += BoundEnvironment(
                environment, sourceSet.jarTaskName
            )
        }
    }

    fun task(project: Project, flavor: Flavor): TaskProvider<MergeEraJarsTask> =
        project.tasks.named(flavor.taskName, MergeEraJarsTask::class.java)

    /** Attach a configured flavor to the standard `assemble` lifecycle. */
    fun linkAssemble(project: Project, flavor: Flavor) {
        project.pluginManager.withPlugin("java") {
            project.tasks.named("assemble").configure {
                it.dependsOn(task(project, flavor))
            }
        }
    }

    private fun wire(
        project: Project,
        flavor: Flavor,
        taskProvider: TaskProvider<MergeEraJarsTask>
    ) {
        val mod = project.extensions.getByType(ModExtension::class.java)
        val minecraft = project.extensions.findByType(MinecraftExtension::class.java)
        val spec = when (flavor) {
            Flavor.MERGED_ERA -> mod.mergedEraJarSpec
            Flavor.CHIMERA -> mod.chimeraJarSpec
        }
        val plan = select(spec, mod.eraMergeEnvironments)

        taskProvider.configure { t ->
            t.mainFirst.set(spec.mainFirst)
            t.overlapValidation.set(spec.overlapValidation)
            t.manifestAttributes.set(spec.manifestSpec.attributes)
            t.nestedPath.set(spec.nestedJar.path.orElse(project.provider {
                "META-INF/jars/${sanitizedNestedName(project.name)}-nested.jar"
            }))
            t.emitNestedDescriptor.set(spec.nestedJar.emitDescriptor)
            t.nestedEntries.set(spec.nestedJar.nestedEntries)
            t.nestedManifestAttributes.set(spec.nestedJar.manifestSpec.attributes)

            val parent = spec.nestedJar.parent.orElse(mod.id)
                .orElse(project.provider { project.name })
            t.nestedParent.set(parent)
            t.nestedId.set(spec.nestedJar.id.orElse(parent.map { "$it-inner" }))
            t.nestedVersion.set(spec.nestedJar.version.orElse(mod.version))

            // combined output is wired directly so its compile/resources
            // producers remain task dependencies; the parallel path list is
            // the execution-time ordering snapshot consumed by the task.
            val selectedEnvs = plan.mapNotNull { it.environment }
                .distinctBy { it.environment.environmentId }
            selectedEnvs.forEach { bound ->
                t.sharedOutputs.from(bound.environment.combinedOutputs)
            }
            t.sharedOutputPaths.set(project.provider {
                if (!spec.mainFirst.get()) emptyList()
                else selectedEnvs.flatMap { bound ->
                    bound.environment.combinedOutputs.files.map {
                        it.absoluteFile.normalize().absolutePath
                    }
                }
            })

            plan.forEach { era ->
                val jar = era.override?.jar
                if (jar != null && jar.isPresent) {
                    t.eraJarFiles.from(jar)
                } else if (minecraft != null) {
                    t.eraJarFiles.from(project.provider {
                        artifactFile(project, minecraft, era)
                    })
                }
            }
            t.eraInputs.set(project.provider {
                if (minecraft == null && plan.any { it.environment != null &&
                        it.override?.jar?.isPresent != true }) {
                    throw GradleException(
                        "unimined: ${flavor.taskName}: environment-backed eras " +
                            "require the minecraft plugin; use era(...).jar for manual inputs"
                    )
                }
                plan.map { era ->
                    EraJarInput(
                        era.name,
                        effectivePartition(era.override, loaderName(era)),
                        artifactFile(project, minecraft, era).absoluteFile.normalize().absolutePath
                    )
                }
            })
            t.dependsOn(project.provider {
                plan.mapNotNull { era ->
                    if (era.override?.jar?.isPresent == true || minecraft == null) null
                    else artifactTaskName(project, minecraft, era)
                }
            })

            spec.nestedJar.physicalEntries.forEach { (_, files) ->
                t.additionalNestedJarFiles.from(files)
            }
            t.additionalNestedInputs.set(project.provider {
                spec.nestedJar.physicalEntries.map { (target, files) ->
                    AdditionalNestedJarInput(
                        target,
                        files.files.map { it.absoluteFile.normalize().absolutePath }
                    )
                }
            })
        }
    }

    /** Compute the era plan after DSL evaluation. */
    internal fun select(
        spec: EraMergeSpec,
        environments: List<BoundEnvironment>
    ): List<PlannedEra> {
        val auto = environments.filter { bound ->
            val env = bound.environment
            !env.isAuxEnv.getOrElse(false) &&
                env.version.orNull != null &&
                env.defaultRemapJar.getOrElse(true)
        }
        val manual = if (spec.explicitEras) emptyList() else spec.eraOverrides
            .filter { (name, override) ->
                override.jar.isPresent && auto.none { it.environment.environmentId == name }
            }
            .map { it.key }
        val names = if (spec.explicitEras) spec.eraNames.get()
        else (auto.map { it.environment.environmentId } + manual).distinct()
        return names.distinct().map { name ->
            val env = environments.firstOrNull { it.environment.environmentId == name }
            val override = spec.eraOverrides[name]
            if (env == null && override?.jar?.isPresent != true) {
                throw GradleException(
                    "unimined: era merge: era '$name' is neither a bound output " +
                        "environment nor a manual era with era(\"$name\") { jar.set(...) }"
                )
            }
            PlannedEra(name, env, override)
        }
    }

    internal fun effectivePartition(override: EraInputSpec?, loader: String?): String {
        override?.partition?.orNull?.let { value ->
            val normalized = value.lowercase()
            if (normalized !in setOf("nest", "flatten")) {
                throw GradleException(
                    "unimined: era merge: invalid partition '$value' — " +
                        "expected one of: nest, flatten"
                )
            }
            return normalized
        }
        val descriptor = loader?.let { LoaderRegistry.resolveBuiltin(it) }
        return if (descriptor?.metadata?.nestedJarsKey != null) "nest" else "flatten"
    }

    private fun loaderName(era: PlannedEra): String? =
        era.override?.loader?.orNull
            ?: era.environment?.environment?.loaderSpec?.orNull?.name

    private fun artifactFile(
        project: Project,
        minecraft: MinecraftExtension?,
        era: PlannedEra
    ): File {
        era.override?.jar?.takeIf { it.isPresent }?.let {
            return it.get().asFile
        }
        if (minecraft == null) {
            throw GradleException(
                "unimined: era merge: era '${era.name}' has no explicit jar and " +
                    "the minecraft plugin is not applied"
            )
        }
        val taskName = artifactTaskName(project, minecraft, era)
        val task = project.tasks.getByName(taskName) as? AbstractArchiveTask
            ?: throw GradleException(
                "unimined: era merge: '$taskName' is not an archive task for era '${era.name}'"
            )
        return task.archiveFile.get().asFile
    }

    private fun artifactTaskName(
        project: Project,
        minecraft: MinecraftExtension,
        era: PlannedEra
    ): String {
        val bound = era.environment ?: throw GradleException(
            "unimined: era merge: era '${era.name}' has no bound environment"
        )
        val jarTask = bound.jarTaskName
        val remapTask = "remap${jarTask.replaceFirstChar(Char::uppercase)}"
        return if (minecraft.isIdentityRemap(bound.environment.environmentId).get()) {
            jarTask
        } else if (project.tasks.findByName(remapTask) != null) {
            remapTask
        } else {
            throw GradleException(
                "unimined: era merge: no production artifact task '$remapTask' for " +
                    "era '${era.name}'. Enable defaultRemapJar or set " +
                    "era(\"${era.name}\") { jar.set(...) }."
            )
        }
    }

    /** Path-safe project-name derivative for the default nested jar path. */
    private fun sanitizedNestedName(projectName: String): String =
        projectName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "mod" }

}
