package xyz.wagyourtail.unimined.transform.internal.wiring

import xyz.wagyourtail.commonskt.utils.capitalized
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.reportProblem
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.minecraft.AccessDslSpec
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.transform.internal.DepAccessScan
import xyz.wagyourtail.unimined.transform.internal.authoredResourceDirs
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.task.ValidateAccessDocsTask
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
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
 * - the `mod*` buckets (`modApi`/`modImplementation`/`modCompileOnly`/
 *   `modCompileOnlyApi`/`modRuntimeOnly`/`modLocalRuntime`) get remapped
 *   views via [RemapExtension.remappedViewFor] and are appended to the
 *   source set's classpaths — keeping `Gradle: g:a:v` library identity
 *   in IDEs.
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

        // inline `access { }` declarations + AT/AW/CT files discovered
        // in the env's resources — registered below `modBatch` (the
        // dep-declared access scan needs the raw dep jars); still
        // executes post-DSL once the env's loader + DSL are settled

        // Yarn's documented 1.x `requires("intermediary")` chain is a
        // mapping-source fact, not a jarmod behavior. Contribute it in
        // the loader-neutral wiring so gating jarmod processors to the
        // jarmod family cannot silently remove it from loaderless or
        // non-fabric environments.
        contributeYarnIntermediary(environment)

        // the loader-declared java floor (cleanroom→21, ornithe→17, …)
        // raises the environment's effective java version — a
        // convention, so a user's explicit `javaVersion` wins. Merged
        // multi-loader envs take the strictest floor across every
        // declared loader.
        environment.javaVersion.convention(project.provider {
            environment.loaderSpecs.mapNotNull { spec ->
                LoaderRegistry.resolveBuiltin(spec.name)?.javaVersion
                    ?.invoke(environment.version.get(), spec.version)
            }.maxOrNull()
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
                ns.singleOrNull() ?: project.failProblem(
                    "env/mod-source-namespaces",
                    "unimined: environment '$environmentId' declares " +
                    "${ns.size} mod source namespaces",
                    "auto mod remap supports exactly one; declare " +
                    "remap.remappedViews for mixed inputs"
                )
            },
            devNamespace
        )

        // pass-through views over the (declarable-only) buckets — raw
        // artifacts for identity attach, for the remap batch's
        // source-namespace analysis classpath, and for jar-processor
        // spec building
        val modApiDirect = directModView(
            project, environment.modApiConfigurationName
        )
        val modImplementationDirect = directModView(
            project, environment.modImplementationConfigurationName
        )
        val modCompileOnlyDirect = directModView(
            project, environment.modCompileOnlyConfigurationName
        )
        val modCompileOnlyApiDirect = directModView(
            project, environment.modCompileOnlyApiConfigurationName
        )
        val modRuntimeOnlyDirect = directModView(
            project, environment.modRuntimeOnlyConfigurationName
        )
        val modLocalRuntimeDirect = directModView(
            project, environment.modLocalRuntimeConfigurationName
        )
        // compile-only mods join the batch: they sit on the compile
        // classpath next to modImplementation, so sibling inheritance
        // analysis and the dep access scan must see them (loom treats
        // modCompileOnly identically there)
        val modBatch = project.files(
            modApiDirect, modImplementationDirect, modCompileOnlyDirect,
            modCompileOnlyApiDirect, modRuntimeOnlyDirect, modLocalRuntimeDirect
        )

        // inline `access { }` declarations + AT/AW/CT files discovered
        // in the env's resources — materialized once the env's loader
        // (consumed document format) and DSL body are both settled
        project.afterDslEval {
            wireAccessFiles(project, environment, sourceSet, remap, modBatch)
        }

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
                        project.failProblem("transform/param-empty",
                            "unimined: transform param " +
                            "'$what' for env '$environmentId' resolved no value")
                    })
                p.label.set("$environmentId/dev")
                p.surface.set(RemapSurface.JAR)
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
                p.surface.set(RemapSurface.JAR)
                p.sourceNamespace.set(devSrcNamespace)
                p.envSide.set(environment.side.map { it.name })
                p.targetNamespace.set(
                    prodNamespace(project, minecraft, environment))
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

        // Obfuscated aux artifacts (Realms & friends — issue #27) ride
        // their own artifactType so the product pipeline never claims
        // them: env-artifact-scoped steps (userdevExec merge, jarmod
        // patching, the jar-processor spec) stamp merged MC content
        // over any jar fed through them. The aux channel is the
        // dependency pipeline: `dependencyPipelineSteps` drops
        // environment-artifact contributions, keeping the namespace
        // remap + shared contributions (access transforms). aux->raw
        // passes bytes through for raw-view consumers.
        //
        // The raw MC artifact rides every aux remap as a scan alias,
        // exactly like the mod views below: aux classes are absent
        // from the mapping tree (feather & co. carry no rows for
        // them), so tree-only propagation can never resolve their
        // inherited members — callsites into game-jar declarations
        // keep their source names while the game jar's declarations
        // rename, a cross-jar linkage break. The alias folds the game
        // jar's scan into the batch inheritance graph (same-namespace
        // envs index it directly, relocated ones read it
        // name-translated), so member resolution can climb the chain
        // from an aux class into its game-jar ancestors.
        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.auxArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.devArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/aux-dev")
                p.surface.set(RemapSurface.JAR)
                p.sourceNamespace.set(devSrcNamespace)
                p.targetNamespace.set(devNamespace)
                p.envSide.set(environment.side.map { it.name })
                p.mappingFile.set(mcBundleFile)
                p.classpath.from(libraries)
                p.scanAliasJars.from(
                    minecraft.rawView(environmentId)
                        ?: project.failProblem("env/raw-view",
                            "unimined: no raw MC view registered for " +
                                "environment '$environmentId'")
                )
                p.scanAliasFromNs.set(minecraft.sourceNamespace(environmentId))
                p.scanAliasMappingFile.set(mcBundleFile)
                p.stepSpecs.set(
                    remap.dependencyPipelineSteps(
                        environmentId, RemapSurface.JAR))
            }
        }

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.auxArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.prodArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/aux-prod")
                p.surface.set(RemapSurface.JAR)
                p.sourceNamespace.set(devSrcNamespace)
                p.envSide.set(environment.side.map { it.name })
                p.targetNamespace.set(
                    prodNamespace(project, minecraft, environment))
                p.mappingFile.set(mcBundleFile)
                p.classpath.from(libraries)
                p.scanAliasJars.from(
                    minecraft.rawView(environmentId)
                        ?: project.failProblem("env/raw-view",
                            "unimined: no raw MC view registered for " +
                                "environment '$environmentId'")
                )
                p.scanAliasFromNs.set(minecraft.sourceNamespace(environmentId))
                p.scanAliasMappingFile.set(mcBundleFile)
                p.stepSpecs.set(
                    remap.dependencyPipelineSteps(
                        environmentId, RemapSurface.JAR))
            }
        }

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.auxArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.rawArtifactType(environmentId)
            )
            t.parameters { p ->
                p.label.set("$environmentId/aux-raw")
                p.surface.set(RemapSurface.JAR)
                p.sourceNamespace.set(devSrcNamespace)
                p.targetNamespace.set(devSrcNamespace)
                p.envSide.set(environment.side.map { it.name })
                p.stepSpecs.set(emptyList())
            }
        }

        val modApiView = remappedModView(
            project, minecraft, remap, environment,
            environment.modApiConfigurationName, modsBundleFile,
            mcBundleFile, modApiDirect
        )
        val modImplementationView = remappedModView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsBundleFile,
            mcBundleFile, modImplementationDirect
        )
        val modCompileOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modCompileOnlyConfigurationName, modsBundleFile,
            mcBundleFile, modCompileOnlyDirect
        )
        val modCompileOnlyApiView = remappedModView(
            project, minecraft, remap, environment,
            environment.modCompileOnlyApiConfigurationName, modsBundleFile,
            mcBundleFile, modCompileOnlyApiDirect
        )
        val modRuntimeOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsBundleFile,
            mcBundleFile, modRuntimeOnlyDirect
        )
        val modLocalRuntimeView = remappedModView(
            project, minecraft, remap, environment,
            environment.modLocalRuntimeConfigurationName, modsBundleFile,
            mcBundleFile, modLocalRuntimeDirect
        )
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modApiConfigurationName}"
        ] = modApiView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modImplementationConfigurationName}"
        ] = modImplementationView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modCompileOnlyConfigurationName}"
        ] = modCompileOnlyView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modCompileOnlyApiConfigurationName}"
        ] = modCompileOnlyApiView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modRuntimeOnlyConfigurationName}"
        ] = modRuntimeOnlyView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modLocalRuntimeConfigurationName}"
        ] = modLocalRuntimeView

        // dep `-sources.jar` views — the IDE-attach counterpart of the
        // jar views: the bucket's docs variants (`category=documentation`,
        // `docsType=sources`) resolve each dep's published sources and a
        // per-view transform runs them through the SOURCES pipeline
        // (loom's per-dep `scheduleRemapSources` parity — dep sources
        // stay remappable even when their jar passed the marker scan,
        // since sources jars carry no markers). The `*SourcesDirect`
        // twins are the identity-env pass-throughs; consumers pick by
        // `isIdentityRemap` exactly like the `*Direct`/`*Remapped` jars
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modApiConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modApiConfigurationName, modsBundleFile,
            modApiDirect, libraries
        )
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modImplementationConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsBundleFile,
            modImplementationDirect, libraries
        )
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modCompileOnlyConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modCompileOnlyConfigurationName, modsBundleFile,
            modCompileOnlyDirect, libraries
        )
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modCompileOnlyApiConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modCompileOnlyApiConfigurationName, modsBundleFile,
            modCompileOnlyApiDirect, libraries
        )
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modRuntimeOnlyConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsBundleFile,
            modRuntimeOnlyDirect, libraries
        )
        minecraft.modDevSourcesViewConfigurations[
            "$environmentId:${environment.modLocalRuntimeConfigurationName}"
        ] = remappedModSourcesView(
            project, minecraft, remap, environment,
            environment.modLocalRuntimeConfigurationName, modsBundleFile,
            modLocalRuntimeDirect, libraries
        )
        directModSourcesView(
            project, environment.modApiConfigurationName
        )
        directModSourcesView(
            project, environment.modImplementationConfigurationName
        )
        directModSourcesView(
            project, environment.modCompileOnlyConfigurationName
        )
        directModSourcesView(
            project, environment.modCompileOnlyApiConfigurationName
        )
        directModSourcesView(
            project, environment.modRuntimeOnlyConfigurationName
        )
        directModSourcesView(
            project, environment.modLocalRuntimeConfigurationName
        )

        // identity environments (prod namespace == dev namespace)
        // consume mod jars as-is — routing them through the remap
        // transform would only duplicate artifacts to express a
        // nonexistent namespace hop. The pick between direct and
        // remapped defers to resolution time so mappings never resolve
        // during configuration — and
        // the provider shape matters under the configuration cache:
        // `fileCollection.from(provider { conf })` (a CallableProvider
        // yielding a Configuration) resolves to EMPTY on CC-restore
        // runs — Gradle loses the conf's resolution link. The working
        // idiom is a provider chain of domain-object providers:
        // `identity.flatMap { configurations.named(branch) }` keeps
        // each branch a first-class conf reference that resolves the
        // live view (and its transforms) at execution.
        val identity = minecraft.isIdentityRemap(environmentId)
        val modApiFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modApiDirect.name else modApiView.name
                )
            }
        )
        val modImplementationFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modImplementationDirect.name else modImplementationView.name
                )
            }
        )
        val modCompileOnlyFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modCompileOnlyDirect.name else modCompileOnlyView.name
                )
            }
        )
        val modCompileOnlyApiFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modCompileOnlyApiDirect.name else modCompileOnlyApiView.name
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
        val modLocalRuntimeFiles = project.files(
            identity.flatMap { id ->
                project.configurations.named(
                    if (id) modLocalRuntimeDirect.name else modLocalRuntimeView.name
                )
            }
        )

        // modApi rides the dev classpaths exactly like modImplementation
        // (compile+runtime); the split is publication-scope only
        sourceSet.compileClasspath += modApiFiles
        sourceSet.compileClasspath += modImplementationFiles
        // compile-only — the remapped (or identity-direct) view, never
        // the raw bucket, and NEVER the runtime classpath: modCompileOnly
        // is compile-scope like java's compileOnly, dev launches and
        // prod packaging both stay unaware of it. modCompileOnlyApi is
        // the same in dev — the api split is publication-scope only
        sourceSet.compileClasspath += modCompileOnlyFiles
        sourceSet.compileClasspath += modCompileOnlyApiFiles
        sourceSet.runtimeClasspath += modApiFiles + modImplementationFiles +
                modRuntimeOnlyFiles + modLocalRuntimeFiles

        // merged multi-loader envs: every non-primary loader spec lands
        // its `loaderCoords` artifact on the compile classpath — the
        // 1.x `merged { fabric; forge }` analog, compile-only (prod/
        // remap/metadata/runs stay with the primary). Families whose
        // secondary contribution is era-probed wire their own bucket
        // (`mergedClasspathSelfManaged` — forge's `forgeMergedLoader`),
        // so the generic bucket skips them
        val mergedLoader = project.configurations.create(
            "mergedLoader${environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.withDependencies { deps ->
                environment.loaderSpecs.drop(1).forEach { spec ->
                    val descriptor = LoaderRegistry.resolveBuiltin(spec.name)
                        ?: return@forEach
                    if (descriptor.mergedClasspathSelfManaged) return@forEach
                    deps.add(project.dependencies.create(
                        descriptor.loaderCoords(
                            environment.version.get(), spec.version
                        )
                    ))
                }
            }
        }
        project.configurations.named(sourceSet.compileClasspathConfigurationName) {
            it.extendsFrom(mergedLoader)
        }

        // Dependency resolution must describe the JVM the environment
        // actually runs on, not the bytecode level the compile task is
        // configured to emit. Those intentionally differ under jvmdg
        // (compile Java 8 bytecode against a Java-17+ game runtime).
        // Gradle otherwise seeds resolvable classpaths from the compile
        // task's source/target/release settings and then rejects modern
        // authlib/securejarhandler module variants as incompatible.
        // `requiredJavaVersion` itself stays lazy: provider-backed
        // capabilities populate it from version metadata at resolution
        // time, then the user/loader `javaVersion` floor raises it.
        val resolutionJavaVersion = environment.requiredJavaVersion
        listOf(
            sourceSet.compileClasspathConfigurationName,
            sourceSet.runtimeClasspathConfigurationName
        ).forEach { configurationName ->
            project.configurations.named(configurationName) { conf ->
                conf.attributes.attributeProvider(
                    TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                    resolutionJavaVersion
                )
            }
        }

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
                    ?: project.failProblem("env/raw-view",
                        "unimined: no raw MC view registered for " +
                            "environment '$environmentId'")
            )
            spec.scanAliasFromNs.set(minecraft.sourceNamespace(environmentId))
            spec.scanAliasMappingFile.set(mcBundleFile)
            spec.hasScanAlias = true
            // env force-remap escapes (`environment.forceRemap(...)`) —
            // forwarded as view filters so matching deps bypass the
            // marker scan even when they carry no markers
            spec.forceRemapFilters.addAll(environment.forceRemapFilters)
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
     * Resolvable view over a mod bucket's docs variants — requests
     * each dep's `-sources.jar` (`category=documentation`,
     * `docsType=sources`: maven's synthesized sources variant or a
     * GMM `sourcesElements`/`withSourcesJar` variant) with a per-view
     * `artifactType` token so the SOURCES-surface
     * [RemapDependencyTransform] registered here remaps it into the
     * dev namespace.
     *
     * Consumers must resolve leniently and filter to module/project
     * component ids: components with no sources variant contribute
     * nothing, and file deps would otherwise contribute their own
     * jar (they match every attribute set).
     *
     * No marker scan — sources jars carry no mod markers, and a
     * dep whose jar remapped needs remapped sources regardless;
     * always-remap is loom's `scheduleRemapSources` parity.
     */
    private fun remappedModSourcesView(
        project: Project,
        minecraft: MinecraftExtension,
        remap: RemapExtension,
        environment: MinecraftEnvironment,
        bucketName: String,
        modsBundleFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
        rawBatchView: Configuration,
        libraries: Configuration
    ): Configuration {
        val environmentId = environment.environmentId
        val artifactType = "unimined-remapped-sources-$environmentId-$bucketName"
        project.dependencies.registerTransform(
            RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                artifactType
            )
            t.parameters { p ->
                p.label.set("$environmentId/$bucketName-sources")
                p.surface.set(RemapSurface.SOURCES)
                // dep sources live in the dep's own namespace —
                // `modSourceNamespaces` like the jar views
                p.sourceNamespace.set(
                    minecraft.modSourceNamespaces(environmentId).map { namespaces ->
                        if (namespaces.size != 1) project.failProblem(
                            "env/mod-source-namespaces",
                            "unimined: environment '$environmentId' declares " +
                                    "${namespaces.size} mod source namespaces " +
                                    "for bucket '$bucketName'",
                            "auto wiring needs exactly one; declare " +
                                    "remap.remappedViews(...) for mixed inputs"
                        )
                        namespaces.single()
                    }
                )
                p.targetNamespace.set(minecraft.devNamespace(environmentId))
                p.mappingFile.set(modsBundleFile)
                // the dep-namespace parse classpath: raw mod siblings +
                // the raw MC artifact + env libraries — the sources'
                // own-namespace references resolvable for the PSI pass
                p.classpath.from(
                    libraries, rawBatchView,
                    minecraft.rawView(environmentId)
                        ?: project.failProblem("env/raw-view",
                            "unimined: no raw MC view registered for " +
                                "environment '$environmentId'")
                )
                p.stepSpecs.set(
                    remap.dependencyPipelineSteps(environmentId, RemapSurface.SOURCES)
                )
            }
        }
        return project.configurations.create("${bucketName}SourcesRemapped") { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(project.configurations.getByName(bucketName))
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType
            )
            conf.attributes.attribute(
                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
                project.objects.named(
                    org.gradle.api.attributes.Category::class.java,
                    org.gradle.api.attributes.Category.DOCUMENTATION
                )
            )
            conf.attributes.attribute(
                org.gradle.api.attributes.DocsType.DOCS_TYPE_ATTRIBUTE,
                project.objects.named(
                    org.gradle.api.attributes.DocsType::class.java,
                    org.gradle.api.attributes.DocsType.SOURCES
                )
            )
        }
    }

    /**
     * Pass-through counterpart of [remappedModSourcesView] for
     * identity environments — resolves the `-sources.jar` docs
     * variants unmodified when the dep namespace already is the dev
     * namespace, like [directModView] does for jars.
     */
    private fun directModSourcesView(
        project: Project,
        bucketName: String
    ): org.gradle.api.artifacts.Configuration =
        project.configurations.create("${bucketName}SourcesDirect") { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(project.configurations.getByName(bucketName))
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            conf.attributes.attribute(
                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
                project.objects.named(
                    org.gradle.api.attributes.Category::class.java,
                    org.gradle.api.attributes.Category.DOCUMENTATION
                )
            )
            conf.attributes.attribute(
                org.gradle.api.attributes.DocsType.DOCS_TYPE_ATTRIBUTE,
                project.objects.named(
                    org.gradle.api.attributes.DocsType::class.java,
                    org.gradle.api.attributes.DocsType.SOURCES
                )
            )
        }

    /**
     * Supply the `official -> intermediary` hop required by yarn-family
     * mapping documents when the user has not declared one. Fabric-family
     * wiring owns this hop itself (including its loader-specific upstream
     * channels and namespace relabeling), so it is excluded here.
     */
    private fun contributeYarnIntermediary(environment: MinecraftEnvironment) {
        environment.mappingsSpec.deferredDefaultSources += {
            val declared = sources
            val hasYarn = declared.any {
                it is MappingsSpecImpl.Source.Maven && ":yarn:" in it.notation
            }
            val hasIntermediary = declared.any {
                it is MappingsSpecImpl.Source.Maven &&
                        it.notation.startsWith("net.fabricmc:intermediary")
            }
            val loaderFamily = environment.loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }?.family
            if (loaderFamily != "fabric" && hasYarn && !hasIntermediary &&
                !environment.mappingsSpec.customIntermediaries.get() &&
                environment.obfuscated.get()
            ) collectDeclared { intermediary() }
            else emptyList()
        }
    }


    /**
     * Materialize access declarations into the env's declared file
     * collections so they apply to the dev Minecraft artifact (and ride
     * the packaging/remap channels like any declared file):
     *
     *  - inline `access { }` rules (own spec + parent-chain specs from
     *    `combineWith`) — emitted in the env's consumed document
     *    format: an `accesstransformer.cfg` doc on AT-family loaders
     *    (FML's `META_INF_ACCESSTRANSFORMER`/`FMLAT_MANIFEST`
     *    discovery), otherwise an `accessWidener` doc, upgraded to
     *    `classTweaker v1` when CT directives are present. Rules with
     *    no analog in the consumed format are dropped with a log note.
     *  - AT/AW/CT files found under the env's resource roots —
     *    classified by header then extension and added to the
     *    format-matching collection (an `accessWidener`/`classTweaker`
     *    doc joins `accessWideners`, FML AT files join
     *    `accessTransformers`); mismatched loaders simply don't apply
     *    them, while packaging still ships/converts correctly.
     */
    private fun wireAccessFiles(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        remap: RemapExtension,
        depJars: org.gradle.api.file.FileCollection
    ) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val envId = environment.environmentId

        // consumed document format — AT-family loaders discover via
        // FML's mechanisms; everything else takes the AW/CT doc
        val descriptor = environment.loaderSpec.orNull?.name
            ?.let { LoaderRegistry.resolveBuiltin(it) }
        val atFamily = descriptor?.metadata?.accessDiscovery.orEmpty().any {
            it == AccessDiscovery.META_INF_ACCESSTRANSFORMER ||
                it == AccessDiscovery.FMLAT_MANIFEST
        }

        val specs = environment.effectiveAccessSpecs()

        // `access { format = "..." }` — last declaration across the
        // combine chain wins; must agree with a direct
        // `accessFileFormat` property set. Resolved ahead of doc
        // generation: loaderless envs emit the generated doc directly
        // in this format (there is no packaging merge to re-format
        // downstream); loader envs keep it as the canonical packaging
        // override only
        specs.lastOrNull { it.format != null }?.format?.let { fmt ->
            if (environment.accessFileFormat.isPresent &&
                environment.accessFileFormat.get() != fmt
            ) project.failProblem(
                "access/format-conflict",
                "unimined: environment '$envId' — conflicting access " +
                        "doc formats: accessFileFormat=" +
                        "'${environment.accessFileFormat.get()}' vs " +
                        "access { format = '$fmt' }")
            environment.accessFileFormat.set(fmt)
        }

        // emit syntax for the generated doc — AT-family: AT rows;
        // loaderless: `access { format }` else CT (the jar-shipped
        // canonical doc — self-describing header, carries every rule
        // kind, and dependents discover it via the dep-access scan);
        // loader envs: AW (packaging upgrades the merge to CT/AT)
        val format = when {
            atFamily -> AccessDslSpec.Format.AT
            descriptor == null -> when (
                environment.accessFileFormat.orNull?.lowercase()) {
                null, "ct", "classtweaker" -> AccessDslSpec.Format.CT
                "aw", "accesswidener" -> AccessDslSpec.Format.AW
                "at", "accesstransformer",
                "legacy_at", "legacyat" -> AccessDslSpec.Format.AT
                else -> project.failProblem(
                    "access/format",
                    "unimined: environment '$envId' — unknown access doc " +
                            "format '${environment.accessFileFormat.get()}' " +
                            "(expected aw/ct/at)")
            }
            else -> AccessDslSpec.Format.AW
        }

        // loaderless docs write under a `/jar` subtree — the jar task
        // consumes that dir so jar-path layout (META-INF/) is
        // preserved; loader envs keep the flat collection layout
        val genDir = project.layout.buildDirectory.dir(
            "unimined/access/$envId" +
                    if (descriptor == null) "/jar" else "")

        // 1. inline access {} rules → one generated doc under build/
        if (specs.any { it.rules.isNotEmpty() }) {
            val dropped = mutableListOf<String>()
            val lines = specs.flatMap { it.emit(format, dropped) }
            if (dropped.isNotEmpty()) {
                project.reportProblem(
                    "access/rules-dropped",
                    "unimined: environment '$envId' — ${dropped.size} " +
                            "access rule(s) dropped: no " +
                            "${format.name}-format equivalent",
                    dropped.joinToString(", ")
                )
            }
            if (lines.isNotEmpty()) {
                val ctDoc = !atFamily && (format == AccessDslSpec.Format.CT ||
                        specs.any { it.needsClassTweaker })
                // loaderless names are jar paths — `*_at.cfg` needs
                // META-INF/ for dep-scan discovery; `access { fileName }`
                // overrides the whole path. Loader names are collection
                // labels only (packaging picks the canonical path)
                val name = when {
                    format == AccessDslSpec.Format.AT ->
                        if (descriptor == null) "META-INF/$envId-access_at.cfg"
                        else "accesstransformer.cfg"
                    ctDoc -> "$envId-access.classtweaker"
                    else -> "$envId-access.accesswidener"
                }
                // materialize on resolution — the dev-namespace header
                // needs mapping data that isn't known at config time;
                // the label canonicalizes like the packaging merge
                // (mojmap-family dev ns → `named`)
                val generated = project.provider {
                    val devNs = FabricNamespaces.canonical(
                        minecraft.devNamespace(envId).get(),
                        minecraft.namedNamespaces(envId).get()
                    )
                    val header = when {
                        format == AccessDslSpec.Format.AT -> null
                        ctDoc -> "classTweaker\tv1\t$devNs"
                        else -> "accessWidener\tv1\t$devNs"
                    }
                    val rel = if (descriptor == null) {
                        environment.accessFileName.orNull ?: name
                    } else name
                    val target = genDir.get().asFile.resolve(rel)
                    val body = (listOfNotNull(header) + lines)
                        .joinToString("\n", postfix = "\n")
                    if (!target.isFile || target.readText() != body) {
                        target.parentFile.mkdirs()
                        target.writeText(body)
                    }
                    target
                }
                // env-local collection — children re-emit inherited
                // rules in their own format instead of inheriting
                // this file through `combineWith` (double-apply)
                environment.generatedAccessFiles.from(generated)

                // publish-side: the `unimined<Env>AccessTransformer
                // Elements` consumable conf carries the canonical doc —
                // created by unimined-publish's own post-eval wiring, so
                // a live-rule catches it regardless of apply order
                project.configurations.configureEach { conf ->
                    if (conf.name ==
                            environment.accessTransformerElementsConfName) {
                        conf.outgoing.artifact(generated) { a ->
                            a.classifier = "access-transformer"
                        }
                    }
                }
            }
        }

        // 2. `access { from(...) }` file declarations — resolved lazily
        //    (provider-backed paths stay deferred), routed by format;
        //    `from(path) { }` optioned entries ride the
        //    `accessFileOverrides` channel like `accessFile(path) { }`
        val fromArgs = specs.flatMap { it.files }
        if (fromArgs.isNotEmpty()) {
            environment.accessWideners.from(project.provider {
                project.files(*fromArgs.toTypedArray()).files.filter {
                    classifyAccessFile(it).let { c ->
                        c == AccessDslSpec.Format.AW || c == AccessDslSpec.Format.CT
                    }
                }
            })
            environment.accessTransformers.from(project.provider {
                project.files(*fromArgs.toTypedArray()).files.filter {
                    classifyAccessFile(it) == AccessDslSpec.Format.AT
                }
            })
        }
        for (nf in specs.flatMap { it.namedFiles }) {
            val file = project.files(nf.path).files.singleOrNull()
                ?: project.failProblem(
                    "access/file",
                    "unimined: environment '$envId' — " +
                            "access { from(${nf.path}) { } } must resolve " +
                            "to exactly one file")
            environment.accessFileOverrides.add(
                DeclaredAccessFile(
                    file.absolutePath, nf.format, nf.output, nf.declare
                )
            )
        }
        // `access { fileName = "..." }` — last declaration across the
        // combine chain wins; must agree with a direct
        // `accessFileName` property set
        specs.lastOrNull { it.fileName != null }?.fileName?.let { name ->
            if (environment.accessFileName.isPresent &&
                environment.accessFileName.get() != name
            ) project.failProblem(
                "access/name-conflict",
                "unimined: environment '$envId' — conflicting access " +
                        "doc names: accessFileName=" +
                        "'${environment.accessFileName.get()}' vs " +
                        "access { fileName = '$name' }")
            environment.accessFileName.set(name)
        }

        // loader-less env (a multi-module `common`) — no family
        // wiring claims the apply steps, so the collections would
        // never reach the dev artifact. Contribute the same defaults
        // a loader env gets: own declarations + dep-declared access
        // docs (loom `transitiveAccessWideners` parity — the class
        // tweaker apply parses AW/CT/AT docs uniformly) + the access
        // model for remap analysis + `remap-access` so dep jars'
        // embedded docs rebase through the mod remap.
        if (descriptor == null) {
            val envSpec = remap.sourceSet(envId)

            // own declarations — headerless user ATs are dev-named
            // (1.x `at2aw` convention)
            val ownSpec = project.objects.newInstance(
                ApplyClassTweakerStepSpec::class.java)
            ownSpec.classTweakerFiles.from(
                environment.accessWideners,
                environment.accessTransformers,
                environment.generatedAccessFiles,
            )
            // the resolved dev ns — the raw `environment.devNamespace`
            // property is unset in the common case (its absent value
            // then fails transform-param isolation outright)
            ownSpec.headerlessNamespace.set(minecraft.devNamespace(envId))
            // `access { validate = true }` — own declarations only;
            // dep-declared docs (depSpec below) are never validated
            ownSpec.validate.set(project.provider {
                environment.effectiveAccessSpecs()
                    .any { it.validate }
            })

            // dep-declared access docs — generic jar-entry scan;
            // headerless dep ATs bind to the env's single declared
            // mod source ns (ambiguous/absent → skipped + warned)
            val depCache = UniminedCache.global(project)
                .resolve("dep-access/$envId")
            val depScan = project.provider {
                val depNs = environment.modSourceNamespaces
                    .orNull?.singleOrNull()
                depJars.files.filter { it.isFile && it.extension == "jar" }
                    .map { jar ->
                        DepAccessScan.scan(
                            jar.toPath(), depCache, depNs
                        ) { msg ->
                            project.reportProblem(
                                "access/dep-scan",
                                "unimined: environment '$envId' — $msg")
                        }
                    }
            }
            val depSpec = project.objects.newInstance(
                ApplyClassTweakerStepSpec::class.java)
            // dep ATs — FML semantics apply them in full
            depSpec.classTweakerFiles.from(depScan.map { results ->
                results.flatMap { it.ats }.map { it.toFile() }
            })
            depSpec.headerlessNamespace.set(
                environment.modSourceNamespaces.map { it.singleOrNull() })
            // coordinate-consumed access docs
            // (`unimined<Env>AccessTransformer("g:a:v")` deps) —
            // dep-authored, same binding as jar-scanned dep docs
            depSpec.classTweakerFiles.from(environment.consumedAccessDocs)
            // dep AW/CT/synth docs — only `transitive-*` rules cross
            // to dependents. Access docs gate on
            // `transitiveAccessWideners`; synthesized
            // injected-interface docs gate independently on
            // `dependencyInterfaceInjection` (loom's
            // `enableDependencyInterfaceInjection` parity)
            depSpec.dependencyClassTweakerFiles.from(project.provider {
                val hasTransitives = { f: java.io.File ->
                    runCatching {
                        ClassTweaker.parse(
                            f.name, f.readText()).hasTransitiveRules
                    }.getOrDefault(false)
                }
                val awDocs = if (!environment.transitiveAccessWideners.get()) {
                    emptyList()
                } else depScan.get().flatMap { it.declared }
                    .map { it.toFile() }.filter(hasTransitives)
                val iiDocs = if (!environment.dependencyInterfaceInjection.get()) {
                    emptyList()
                } else depScan.get().flatMap { it.interfaceInjections }
                    .map { it.toFile() }.filter(hasTransitives)
                awDocs + iiDocs
            })

            // remap-analysis overlay (plan §3 role 1): own + dep
            // rules in full — engines rebase sections through the
            // tree, so widened members resolve correctly when mod
            // deps are remapped
            envSpec.contributeAccessModel(project.provider {
                AccessModel(
                    (environment.accessWideners.files +
                            environment.accessTransformers.files +
                            environment.generatedAccessFiles.files)
                        .filter { it.isFile }
                        .map {
                            ClassTweaker.parse(it.name, it.readText())
                                .toAccessModelSection(it.name)
                        } +
                            depScan.get().flatMap { r ->
                                (r.declared + r.interfaceInjections +
                                        r.ats).map {
                                    ClassTweaker.parse(
                                        it.fileName.toString(),
                                        it.toFile().readText())
                                        .toAccessModelSection(
                                            it.fileName.toString())
                                }
                            }
                )
            })

            envSpec.contributeStep(RemapSurface.JAR, ownSpec)
            envSpec.contributeStep(RemapSurface.JAR, depSpec)
            // dep jars' embedded access docs rebase through their
            // prod→dev remap (formats default to aw/at/ct)
            envSpec.contributeStep(
                RemapSurface.JAR, AccessRemapStepSpec())

            // ship the access docs inside the env's jar — dependents
            // consuming a `common` module as a plain dependency
            // discover them through the same scan loaderless dep
            // jars get (loom `architectury.common.json` semantics:
            // `transitive-*` rules cross to dependents, the rest stay
            // env-local). remapJar mirrors jar.rootSpec, so the
            // embedded doc is rebased dev→prod alongside the jar's
            // other contents
            project.tasks.matching { it.name == sourceSet.jarTaskName }
                .withType(org.gradle.jvm.tasks.Jar::class.java)
                .configureEach { jar ->
                    // generated canonical doc — the provider resolves
                    // the env-local collection first so the doc is
                    // written before the dir is consumed. An absent
                    // get() fails jar's dependency visiting, so yield
                    // an empty list rather than null
                    jar.from(project.provider {
                        environment.generatedAccessFiles.files
                            .firstOrNull { it.isFile }
                            ?.let { listOf(genDir.get().asFile) }
                            ?: emptyList()
                    })
                    // `from(path) { output = ... }` — declared jar
                    // paths; `declare=false` marks dev-apply-only
                    // files that must not ship
                    for (ov in environment.accessFileOverrides.get()) {
                        if (!ov.declare) continue
                        val rel = ov.output
                            ?: accessJarEntry(java.io.File(ov.path).name)
                            ?: continue
                        jar.from(ov.path) { spec ->
                            val dir = rel.substringBeforeLast('/', "")
                            if (dir.isNotEmpty()) spec.into(dir)
                            spec.rename { rel.substringAfterLast('/') }
                        }
                    }
                    // collection files outside the resource roots —
                    // they wouldn't reach the jar otherwise; files
                    // under srcDirs already ship at their resource path
                    jar.from(project.provider {
                        val resRoots = sourceSet.resources.srcDirs
                            .map { it.canonicalFile.path + "/" }
                        val declared = environment.accessFileOverrides
                            .get()
                            .map { java.io.File(it.path).canonicalFile }
                            .toSet()
                        (environment.accessWideners.files +
                                environment.accessTransformers.files)
                            .map { it.canonicalFile }
                            .filter { it.isFile }
                            .filter { f ->
                                resRoots.none { f.path.startsWith(it) } &&
                                        f !in declared
                            }
                    }) { spec ->
                        spec.eachFile { d ->
                            accessJarEntry(d.name)?.let { d.path = it }
                        }
                    }
                }
        }

        // 3. AT/AW/CT files discovered under the env's authored resource
        //    roots — header first, extension fallback; format-matched
        //    collection
        val awFiles = mutableListOf<java.io.File>()
        val atFiles = mutableListOf<java.io.File>()
        for (dir in authoredResourceDirs(project, sourceSet)) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                when (classifyAccessFile(f)) {
                    AccessDslSpec.Format.AT -> atFiles += f
                    AccessDslSpec.Format.AW, AccessDslSpec.Format.CT -> awFiles += f
                    null -> {}
                }
            }
        }
        environment.accessWideners.from(awFiles)
        environment.accessTransformers.from(atFiles)

        // `validate<Env>Access` — loom `validateAccessWidener` parity:
        // own docs' declared targets against the dev artifact, no
        // remap. `check` only depends on it when the env declares
        // access docs — resolving the dev MC view materializes the
        // whole pipeline, so an unconditional dep would cost every
        // `check` a Minecraft build for nothing
        val hasOwnAccessDecls = specs.any {
            it.rules.isNotEmpty() || it.namedFiles.isNotEmpty() ||
                    it.files.isNotEmpty()
        } || environment.accessFileOverrides.get().isNotEmpty() ||
                environment.accessWideners.from.isNotEmpty() ||
                environment.accessTransformers.from.isNotEmpty()
        if (hasOwnAccessDecls) {
            val devView = project.configurations.getByName(
                environment.minecraftDevViewName)
            // verb<Env>Noun naming: `validateAccess` for `main`
            // (elided), `validate<Fabric>Access` otherwise; the
            // pre-convention `validateMainAccess` name stays as a
            // deprecated alias
            val validateTask = project.tasks.register(
                if (envId == "main") "validateAccess"
                else "validate${envId.replaceFirstChar(Char::uppercase)}Access",
                ValidateAccessDocsTask::class.java
            ) { t ->
                t.group = "verification"
                t.description = "Validates the '$envId' environment's " +
                        "access documents' declared targets against " +
                        "the dev Minecraft artifact"
                t.accessDocs.from(
                    environment.accessWideners,
                    environment.accessTransformers,
                    environment.generatedAccessFiles
                )
                // resolved dev ns throughout — the raw
                // `environment.devNamespace` property is commonly
                // unset, and `mappingFile` (an @InputFile) queries its
                // providers during task-dependency resolution
                t.headerlessNamespace.set(minecraft.devNamespace(envId))
                t.targetNamespace.set(minecraft.devNamespace(envId))
                t.mappingFile.set(minecraft.mappingBundleFile(
                    envId, minecraft.devNamespace(envId),
                    minecraft.devNamespace(envId)))
                t.minecraftJar.set(project.layout.file(
                    devView.elements.map { it.single().asFile }))
            }
            if (envId == "main") {
                project.registerDeprecatedTaskAlias(
                    "validateMainAccess", validateTask)
            }
            project.tasks.named("check") { it.dependsOn(validateTask) }
        }
    }

    /** Jar path a bundled access file is discoverable at — headered
     *  docs ride the root, headerless ATs need `META-INF/` for the
     *  `accesstransformer.cfg`/`*_at.cfg` dep-scan conventions.
     *  `null` for names no convention can reach. */
    private fun accessJarEntry(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".accesswidener") || lower.endsWith(".aw") ||
                    lower.endsWith(".classtweaker") ||
                    lower.endsWith(".ct") -> name
            lower == "accesstransformer.cfg" ||
                    lower.endsWith("_at.cfg") -> "META-INF/$name"
            lower.endsWith(".at") ->
                "META-INF/${name.substringBeforeLast('.')}_at.cfg"
            else -> null
        }
    }

    /** Classify a resource file as an access document — `accessWidener`
     *  /`classTweaker` headers win over extension (`accesstransformer.cfg`
     *  and `*_at.cfg` name conventions, `.aw`/`.accesswidener`,
     *  `.ct`/`.classtweaker`, `.at`). Non-access files return `null`. */
    private fun classifyAccessFile(file: java.io.File): AccessDslSpec.Format? {
        val name = file.name.lowercase()
        if (name == "accesstransformer.cfg" || name.endsWith("_at.cfg")) {
            return AccessDslSpec.Format.AT
        }
        val header = runCatching {
            file.bufferedReader().use { it.readLine() }
        }.getOrNull()?.trim() ?: ""
        return when {
            header.startsWith("accessWidener") -> AccessDslSpec.Format.AW
            header.startsWith("classTweaker") -> AccessDslSpec.Format.CT
            name.endsWith(".accesswidener") || name.endsWith(".aw") ->
                AccessDslSpec.Format.AW
            name.endsWith(".classtweaker") || name.endsWith(".ct") ->
                AccessDslSpec.Format.CT
            name.endsWith(".at") -> AccessDslSpec.Format.AT
            else -> null
        }
    }

}
