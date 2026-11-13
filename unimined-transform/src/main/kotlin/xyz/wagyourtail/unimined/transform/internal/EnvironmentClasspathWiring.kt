package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
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

        // declared pipeline contributions — claim-agnostic: stepSpecs +
        // functional transform steps push onto the remap env, inline
        // execute() glue runs in declaration order. Deferred past
        // evaluation so family-installed probeResolvers (forge-era
        // select("era")) are in place; idempotent with the claim-side
        // application in CustomTransformer
        project.extensions.findByType(TransformExtension::class.java)
            ?.sourceSet(environmentId)
            ?.let { spec ->
                project.afterDslEval {
                    spec.applyDeclaredPipeline(project, environment)
                }
            }

        // the loader-declared java floor (cleanroom→21, ornithe→17, …)
        // raises the environment's effective java version — a
        // convention, so a user's explicit `javaVersion` wins
        environment.javaVersion.convention(environment.loaderSpec.map {
            LoaderRegistry.resolveBuiltin(it.name)?.javaVersion
                ?.invoke(environment.version.get(), it.version)
        })

        // `env.jvmdowngrader` is strictly opt-in (user sets it) — no
        // descriptor convention: jvmdg rewriting is off by default

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
        // Bundle file providers — evaluated at transform execution
        // (dependency resolution), since artifact transforms cannot carry
        // task dependencies on a bundle producer. File transport keeps
        // the serialized tree out of param values/CC payloads.
        val mcBundleFile = minecraft.mappingBundleFile(
            environmentId,
            devSrcNamespace, devNamespace
        )
        val modsBundleFile = minecraft.mappingBundleFile(
            environmentId,
            minecraft.modSourceNamespaces(environmentId).map { ns ->
                ns.singleOrNull() ?: throw org.gradle.api.GradleException(
                    "unimined: environment '$environmentId' declares " +
                    "${ns.size} mod source namespaces — auto mod remap " +
                    "supports exactly one; declare remap.remappedViews " +
                    "for mixed inputs"
                )
            },
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
        // jar processors split by stage: pre-remap processors (jarMod
        // merges etc. — prod-named content) run before the pipeline's
        // remap step so the renamer carries them; post-remap processors
        // keep their trailing position
        val jarProcessorsPreSpec = JarProcessorWiring.stepSpec(
            project, minecraft, environment, envSpec, modBatch, preRemap = true
        )
        val jarProcessorsPostSpec = JarProcessorWiring.stepSpec(
            project, minecraft, environment, envSpec, modBatch, preRemap = false
        )
        JarProcessorWiring.wireMappings(
            minecraft, environment, envSpec, modBatch
        )

        // the env's prod jar — emitted mid-pipeline by the `emit` tap
        // `pipelineSteps` inserts between the original-namespace
        // contributions and the dev remap. Consumers resolve the path
        // lazily: the file materializes when the dev (or modsource)
        // transform runs, so step/processor spec contexts must defer
        // reads to execution like any other env artifact.
        val prodEmission = UniminedCache.emitted(project)
            .resolve("$environmentId-prod.jar")
        val priorProd = envSpec.envArtifactBindings["mc:prod"]
        envSpec.envArtifactBindings["mc:prod"] = {
            listOf(prodEmission) + priorProd?.invoke().orEmpty()
        }

        // identity envs (conversion products — dev ns == source ns)
        // normally shortcut the dev classpath to the raw artifact, but
        // the raw→dev transform is the only host for env-scoped pipeline
        // work: jar processors, contributed steps, declared JAR steps.
        // Lazily probed at classpath resolution — contributions can land
        // after this wiring runs.
        minecraft.materializeDevArtifact[environmentId] = project.provider {
            envSpec.effectiveJarProcessors().isNotEmpty() ||
                    remap.sourceSet(environmentId).let { spec ->
                        spec.configuredSurfaces()[RemapSurface.JAR]?.steps?.isNotEmpty() == true ||
                                spec.contributedPreSteps(RemapSurface.JAR).isNotEmpty() ||
                                spec.contributedSteps(RemapSurface.JAR).isNotEmpty()
                    }
        }

        // 1.x `fixInners` (default `obfuscated`) + `mc < 1.8.2` gate —
        // the remapped MC artifact gets missing InnerClasses entries
        // rebuilt for obf-era envs. Mod-jar and dep-view pipelines
        // share the spec list but never inject this default, so an
        // unset flag stays off there (1.x ran it on the MC artifact
        // only). Copies the spec: user-declared specs are shared
        // across consumers, so a pipeline-scoped default must not
        // mutate them.
        val envFixInners: (xyz.wagyourtail.unimined.remap.spi.StepSpec) ->
                xyz.wagyourtail.unimined.remap.spi.StepSpec = { spec ->
            if (spec is RemapStepSpec && spec.fixInnerClasses == null &&
                environment.obfuscated.get() &&
                MinecraftVersions.compare(
                    environment.version.get(), "1.8.2") < 0
            ) RemapStepSpec(spec.engine, spec.jsr305ToJetbrains).apply {
                fixInnerClasses = true
            } else spec
        }

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
                fun <T : Any> Provider<T>.orThrowNamed(what: String): Provider<T> =
                    map { it }.orElse(project.provider {
                        throw GradleException("unimined: transform param " +
                            "'$what' for env '$environmentId' resolved no value")
                    })
                p.label.set("$environmentId/dev")
                p.sourceNamespace.set(devSrcNamespace.orThrowNamed("sourceNamespace"))
                p.targetNamespace.set(devNamespace.orThrowNamed("targetNamespace"))
                p.envSide.set(environment.side.map { it.name })
                p.mappingFile.set(mcBundleFile)
                p.classpath.from(libraries)
                p.stepSpecs.set(
                    remap.pipelineSteps(environmentId, RemapSurface.JAR).map { steps ->
                        // ModArtifactOnly (e.g. modArtifacts refmaps)
                        // applies to mod jars, not the MC artifact
                        listOf(jarProcessorsPreSpec) +
                            steps.map { envFixInners(it) }
                                .filter { it !is ModArtifactOnlyStepSpec } +
                            jarProcessorsPostSpec
                    }
                )
            }
        }

        // MC in the production namespace — only resolved when prod
        // differs from the source namespace (userdev forge's searge
        // case); the prod view consumes it for prod launches and on the
        // mod-remap analysis classpath
        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.rawArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.prodArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/prod")
                p.sourceNamespace.set(devSrcNamespace)
                p.envSide.set(environment.side.map { it.name })
                p.targetNamespace.set(prodNamespace(minecraft, environment))
                p.mappingFile.set(mcBundleFile)
                p.classpath.from(libraries)
                // remap-only — the dev pipeline's jar processors are
                // environment-artifact-scoped, not modclasspath-scoped
                p.stepSpecs.set(
                    remap.pipelineSteps(environmentId, RemapSurface.JAR).map { steps ->
                        listOf(jarProcessorsPreSpec) +
                            steps.map { envFixInners(it) }
                                .filter { it !is ModArtifactOnlyStepSpec } +
                            jarProcessorsPostSpec
                    }
                )
            }
        }

        val modImplementationView = remappedModView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsBundleFile,
            mcBundleFile, modImplementationDirect
        )
        val modRuntimeOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsBundleFile,
            mcBundleFile, modRuntimeOnlyDirect
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
        // the pick between direct and remapped defers to resolution
        // time so mappings never resolve during configuration — and
        // the provider shape matters under the configuration cache:
        // `fileCollection.from(provider { conf })` (a CallableProvider
        // yielding a Configuration) resolves to EMPTY on CC-restore
        // runs — Gradle loses the conf's resolution link. The working
        // idiom is a provider chain of domain-object providers:
        // `identity.flatMap { configurations.named(branch) }` keeps
        // each branch a first-class conf reference that resolves the
        // live view (and its transforms) at execution.
        val identity = minecraft.isIdentityRemap(environmentId)
        val modImplementationFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modImplementationDirect.name else modImplementationView.name
                )
            }
        )
        val modRuntimeOnlyFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modRuntimeOnlyDirect.name else modRuntimeOnlyView.name
                )
            }
        )

        sourceSet.compileClasspath += modImplementationFiles
        sourceSet.runtimeClasspath += modImplementationFiles + modRuntimeOnlyFiles

        ProductionJarWiring.wire(
            project, environment, sourceSet,
            NestedJarsWiring.plan(
                project, environment, minecraft,
                project.extensions.findByType(TransformExtension::class.java)
            )
        )
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
        modsBundleFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
        mcBundleFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
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
            spec.mappingFile.set(modsBundleFile)
            spec.hasLazyMappingFile = true
            // every transform invocation gets the complete raw mod set
            // on its analysis classpath — member propagation needs the
            // siblings a jar actually extends/implements — plus the MC
            // artifact in the jar's *source* namespace (mod classes
            // inherit from MC classes; loom keeps getMinecraftJars(
            // INTERMEDIARY) on readClassPath for the same reason)
            spec.classpath.from(
                project.configurations.getByName(environment.minecraftLibrariesConfigurationName),
                rawBatchView
            )
            // ...carried as a scan alias, not a remapped jar: the remap
            // engine folds the MC artifact's name-translated
            // inheritance scan into the shared batch graph, so no
            // modsource jar is produced and no second full MC remap
            // runs. When modSource == source (identity envs) the jar
            // just indexes into the classpath unchanged.
            spec.scanAliasJars.from(
                minecraft.rawView(environmentId)
                    ?: error("no raw MC view registered for environment '$environmentId'")
            )
            spec.scanAliasFromNs.set(minecraft.sourceNamespace(environmentId))
            spec.scanAliasMappingFile.set(mcBundleFile)
            spec.hasScanAlias = true
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

}
