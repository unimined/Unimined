package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.AccessDslSpec
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
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

        // inline `access { }` declarations + AT/AW/CT files discovered
        // in the env's resources — registered below `modBatch` (the
        // dep-declared access scan needs the raw dep jars); still
        // executes post-DSL once the env's loader + DSL are settled

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

        val specs = environment.inheritedAccessSpecs + environment.accessDsl

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
            ) throw GradleException(
                "conflicting access doc formats: accessFileFormat=" +
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
                else -> throw GradleException(
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
                project.logger.warn(
                    "unimined: environment '$envId' — ${dropped.size} access " +
                            "rule(s) dropped: no ${format.name}-format equivalent " +
                            "(${dropped.joinToString(", ")})"
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
                ?: throw GradleException(
                    "access { from(${nf.path}) { } } must resolve to " +
                            "exactly one file")
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
            ) throw GradleException(
                "conflicting access doc names: accessFileName=" +
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
            ownSpec.headerlessNamespace.set(environment.devNamespace)

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
                            project.logger.warn(
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
            // dep AW/CT/synth docs — only `transitive-*` rules cross
            // to dependents; flag-gated like the loader wirings
            depSpec.dependencyClassTweakerFiles.from(project.provider {
                if (!environment.transitiveAccessWideners.get()) {
                    emptyList()
                } else depScan.get().flatMap { it.declared }
                    .map { it.toFile() }
                    .filter { f ->
                        runCatching {
                            ClassTweaker.parse(
                                f.name, f.readText()).hasTransitiveRules
                        }.getOrDefault(false)
                    }
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
                                (r.declared + r.ats).map {
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
                    // written before the dir is consumed
                    jar.from(project.provider {
                        environment.generatedAccessFiles.files
                            .firstOrNull { it.isFile }
                            ?.let { genDir.get().asFile }
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

        // 3. AT/AW/CT files discovered under the env's resource roots —
        //    header first, extension fallback; format-matched collection
        val awFiles = mutableListOf<java.io.File>()
        val atFiles = mutableListOf<java.io.File>()
        for (dir in sourceSet.resources.srcDirs) {
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
