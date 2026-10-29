package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Wires one bound environment's dev-namespace artifacts onto its source
 * set's classpaths.
 *
 * - `<env>MinecraftDev` — resolvable view over the minecraft stub bucket
 *   requesting `unimined-mc-dev-<env>`; Gradle chains the registered
 *   `jar → raw` and `raw → dev` transforms, so the dev artifact
 *   materializes at resolution time (also during IDE sync).
 * - `raw → dev` is the environment's JAR remap pipeline
 *   ([RemapDependencyTransform]); namespaces arrive as lazy providers so
 *   nothing resolves mappings at configuration time.
 * - `modImplementation`/`modRuntimeOnly` buckets get remapped views via
 *   [RemapExtension.remappedViewFor] and are appended to the source set's
 *   classpaths — keeping `Gradle: g:a:v` library identity in IDEs.
 */
internal object EnvironmentClasspathWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentId = environment.environmentId

        // the loader-declared java floor (cleanroom→21, ornithe→17, …)
        // raises the environment's effective java version — a
        // convention, so a user's explicit `javaVersion` wins
        environment.javaVersion.convention(environment.loaderSpec.map {
            LoaderRegistry.resolveBuiltin(it.name)?.javaVersion
        })

        val minecraftBucket = project.configurations.getByName(
            environment.minecraftConfigurationName
        )
        val libraries = project.configurations.getByName(
            environment.minecraftLibrariesConfigurationName
        )

        val devView = project.configurations.create(environment.minecraftDevViewName) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(minecraftBucket)
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.devArtifactType(environmentId)
            )
        }
        minecraft.devViewConfigurations[environmentId] = devView

        val devNamespace = minecraft.devNamespace(environmentId)
        // The namespace of content entering the core remap — normally the
        // source namespace, but loader pre-steps may relocate content
        // (forge userdev's binpatch substitutes the srg-named clean jar).
        val devSrcNamespace = environment.devRemapSourceNamespace
            .orElse(minecraft.sourceNamespace(environmentId))
        // Bundle JSON providers — evaluated at transform execution
        // (dependency resolution), since artifact transforms cannot carry
        // task dependencies on a bundle producer.
        val mcBundleJson = minecraft.mappingBundleJson(
            environmentId,
            devSrcNamespace, devNamespace
        )
        val modsBundleJson = minecraft.mappingBundleJson(
            environmentId,
            minecraft.modSourceNamespaces(environmentId).map { it.single() },
            devNamespace
        )

        // pass-through views over the (declarable-only) buckets — raw
        // artifacts for identity attach, for the remap batch's
        // source-namespace analysis classpath, and for jar-processor
        // spec building
        val modImplementationDirect = directModView(
            project, environment.modImplementationConfigurationName
        )
        val modRuntimeOnlyDirect = directModView(
            project, environment.modRuntimeOnlyConfigurationName
        )
        val modBatch = project.files(modImplementationDirect, modRuntimeOnlyDirect)

        // env-declared jar processors: jar mutation rides the
        // dev-Minecraft transform as a `jar-processors` step; mapping
        // mutations register on the environment's mapping spec. The
        // processor list itself resolves lazily — transform plugins
        // declare processors after this wiring runs.
        val envSpec = project.extensions.getByType(TransformExtension::class.java)
            .sourceSet(environmentId)
        val jarProcessorsSpec = JarProcessorWiring.stepSpec(
            project, minecraft, environment, envSpec, modBatch
        )
        JarProcessorWiring.wireMappings(
            minecraft, environment, envSpec, modBatch
        )

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.rawArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.devArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/dev")
                p.sourceNamespace.set(devSrcNamespace)
                p.targetNamespace.set(devNamespace)
                p.mappingBundleJson.set(mcBundleJson)
                p.classpath.from(libraries)
                p.stepSpecs.set(
                    remap.pipelineSteps(environmentId, RemapSurface.JAR).map { steps ->
                        steps + jarProcessorsSpec
                    }
                )
            }
        }

        // MC in the mod-source namespace — only resolved when
        // modSource differs from the source namespace (fabric's
        // `intermediary` case); the modsource view consumes it on
        // the mod-remap analysis classpath
        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.rawArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.modSourceArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/modsource")
                p.sourceNamespace.set(devSrcNamespace)
                p.targetNamespace.set(
                    minecraft.modSourceNamespaces(environmentId).map { namespaces ->
                        require(namespaces.size == 1) {
                            "environment '$environmentId' declares ${namespaces.size} " +
                                    "mod source namespaces — the modsource MC view needs " +
                                    "exactly one"
                        }
                        namespaces.single()
                    }
                )
                p.mappingBundleJson.set(mcBundleJson)
                p.classpath.from(libraries)
                // remap-only — the dev pipeline's jar processors are
                // environment-artifact-scoped, not modclasspath-scoped
                p.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.JAR))
            }
        }

        val modImplementationView = remappedModView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsBundleJson,
            modImplementationDirect
        )
        val modRuntimeOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsBundleJson,
            modRuntimeOnlyDirect
        )
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modImplementationConfigurationName}"
        ] = modImplementationView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modRuntimeOnlyConfigurationName}"
        ] = modRuntimeOnlyView

        // identity environments (prod namespace == dev namespace)
        // consume mod jars as-is — routing them through the remap
        // transform would only duplicate artifacts to express a
        // nonexistent namespace hop. The pick between direct and
        // remapped defers to resolution time so mappings never resolve
        // during configuration.
        val identity = minecraft.isIdentityRemap(environmentId)
        val modImplementationFiles = project.files(project.provider {
            if (identity.get()) modImplementationDirect else modImplementationView
        })
        val modRuntimeOnlyFiles = project.files(project.provider {
            if (identity.get()) modRuntimeOnlyDirect else modRuntimeOnlyView
        })

        sourceSet.compileClasspath += modImplementationFiles
        sourceSet.runtimeClasspath += modImplementationFiles + modRuntimeOnlyFiles

        ProductionJarWiring.wire(project, environment, sourceSet)
    }

    /**
     * A resolvable pass-through view over a mod bucket — no artifact
     * type attribute, so jars resolve unmodified. Used for identity
     * environments where mod deps already carry the dev namespace.
     */
    private fun directModView(
        project: Project,
        bucketName: String
    ): org.gradle.api.artifacts.Configuration =
        project.configurations.create("${bucketName}Direct") { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(project.configurations.getByName(bucketName))
        }

    private fun remappedModView(
        project: Project,
        minecraft: MinecraftExtension,
        remap: RemapExtension,
        environment: MinecraftEnvironment,
        bucketName: String,
        modsBundleJson: org.gradle.api.provider.Provider<String>,
        rawBatchView: Configuration
    ): Configuration {
        val environmentId = environment.environmentId
        return remap.remappedViewFor(environmentId, bucketName) { spec ->
            spec.artifactTypeToken.set("unimined-remapped-$environmentId-$bucketName")
            // environment-artifact-scoped contributions (loader binary
            // patching/injection) must not run against dependency jars
            spec.stepSpecs.set(
                remap.dependencyPipelineSteps(environmentId, RemapSurface.JAR)
            )
            spec.sourceNamespace.set(
                minecraft.modSourceNamespaces(environmentId).map { namespaces ->
                    require(namespaces.size == 1) {
                        "environment '$environmentId' declares ${namespaces.size} mod " +
                                "source namespaces for bucket '$bucketName' — auto wiring needs " +
                                "exactly one; declare remap.remappedViews(...) for mixed inputs"
                    }
                    namespaces.single()
                }
            )
            spec.targetNamespace.set(minecraft.devNamespace(environmentId))
            spec.mappingBundleJson.set(modsBundleJson)
            spec.hasLazyMappings = true
            // every transform invocation gets the complete raw mod set
            // on its analysis classpath — member propagation needs the
            // siblings a jar actually extends/implements — plus the MC
            // artifact in the jar's *source* namespace (mod classes
            // inherit from MC classes; loom keeps getMinecraftJars(
            // INTERMEDIARY) on readClassPath for the same reason)
            spec.classpath.from(
                project.configurations.getByName(environment.minecraftLibrariesConfigurationName),
                rawBatchView,
                mcSourceArtifact(project, minecraft, environment)
            )
            // environment-declared access-model contributions (AW/AT/CT
            // rules from loader analysis) — merged lazily so the model
            // resolves mod jars only when the transform actually runs
            spec.accessModel.set(project.provider {
                AccessModel.merge(
                    remap.sourceSet(environmentId).accessModelProviders.map { it.get() }
                ).serialize()
            })
        }
    }

    /**
     * The MC artifact in the mod jars' source namespace, resolved
     * lazily — `rawView` when modSource == source (forge: no hop),
     * the `modSourceView` transform otherwise (fabric: official →
     * `intermediary`). Multi-source-namespace environments must
     * declare `remap.remappedViews(...)` themselves.
     */
    private fun mcSourceArtifact(
        project: Project,
        minecraft: MinecraftExtension,
        environment: MinecraftEnvironment
    ): org.gradle.api.provider.Provider<Configuration> {
        val environmentId = environment.environmentId
        return minecraft.modSourceNamespaces(environmentId).map { namespaces ->
            val source = minecraft.sourceNamespace(environmentId).get()
            require(namespaces.size == 1) {
                "environment '$environmentId' declares ${namespaces.size} mod " +
                        "source namespaces — auto mod classpath wiring needs " +
                        "exactly one; declare remap.remappedViews(...) for mixed inputs"
            }
            (if (namespaces.single() == source) {
                minecraft.rawView(environmentId)
            } else {
                minecraft.modSourceView(environmentId)
            }) ?: error("no MC view registered for environment '$environmentId'")
        }
    }

}
