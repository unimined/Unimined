package xyz.wagyourtail.unimined.minecraft.internal.env

import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.internal.provider.LocalStubRepo
import xyz.wagyourtail.unimined.minecraft.internal.provider.ProviderCacheSettings
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.transform.ProductArtifactTransform

/**
 * Environment↔source-set binding seam for [MinecraftExtension] — owns
 * the bind lifecycle (`onEnvironmentBound` replay, mod-configuration
 * creation, [MinecraftEnvironment.bindSourceSet] dispatch), the
 * per-environment classpath wiring (MC artifact buckets, library
 * buckets, resolvable raw/mod-source views, dev-view contribution
 * selection), and the one-shot `ProductArtifactTransform`
 * registration.
 *
 * The view maps themselves ([MinecraftExtension.rawViewConfigurations]
 * and friends) stay on the extension — transform/source plugins write
 * them through the extension surface; this collaborator reads them.
 */
internal class EnvironmentBinding(private val ext: MinecraftExtension) {

    private val project = ext.project
    private val providers = ext.providers
    private val objects = ext.objects

    private val configuredSourceSets = mutableSetOf<String>()
    private val boundEnvironments = linkedMapOf<String, SourceSet>()
    private val boundHandlers =
        mutableListOf<MinecraftExtension.EnvironmentBoundHandler>()
    private val registeredTransformEnvironments = mutableSetOf<String>()

    /**
     * Register [handler] for environment↔source-set binding — invoked
     * immediately for already-bound environments, then for each future
     * binding. Transform/source plugins use this to attach their
     * environment-scoped classpath wiring.
     */
    fun onEnvironmentBound(handler: MinecraftExtension.EnvironmentBoundHandler) {
        boundHandlers += handler
        boundEnvironments.forEach { (environmentId, sourceSet) ->
            handler.onBound(ext.environments.getByName(environmentId), sourceSet)
        }
    }

    internal fun registerModConfigurations(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        ensureModConfigurations(environment)
        if (boundEnvironments.putIfAbsent(environment.environmentId, sourceSet) != null) {
            return
        }
        environment.bindSourceSet(sourceSet)
        wireClasspath(environment, sourceSet)
        boundHandlers.forEach { it.onBound(environment, sourceSet) }
    }

    internal fun ensureModConfigurations(environment: MinecraftEnvironment) {
        if (!configuredSourceSets.add(environment.environmentId)) return

        project.configurations.maybeCreate(
            environment.modApiConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modImplementationConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modCompileOnlyConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modCompileOnlyApiConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modRuntimeOnlyConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        project.configurations.maybeCreate(
            environment.modLocalRuntimeConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }

        // Remapped mod artifacts enter source-set classpaths as files, so
        // Gradle cannot conflict-resolve their transitives against the
        // separate Minecraft-library bucket. Exclude game-library coordinates
        // from each mod dependency's *transitive* graph at resolution time:
        // the game copy is the only copy that can safely win at runtime.
        // These must be dependency-level excludes — configuration-level
        // excludes remove direct user dependencies too, while dependency-level
        // excludes leave an explicit `modImplementation("g:a:v")` override in
        // place and only filter what that dependency pulls in. Resolve the
        // provider library list lazily here — doing so during DSL evaluation
        // would violate the configuration-cache contract.
        listOf(
            environment.modApiConfigurationName,
            environment.modImplementationConfigurationName,
            environment.modCompileOnlyConfigurationName,
            environment.modCompileOnlyApiConfigurationName,
            environment.modRuntimeOnlyConfigurationName,
            environment.modLocalRuntimeConfigurationName
        ).forEach { bucketName ->
            project.configurations.named(bucketName) { conf ->
                conf.withDependencies { deps ->
                    if (!environment.version.isPresent) return@withDependencies
                    val moduleDeps = deps.filterIsInstance<org.gradle.api.artifacts.ModuleDependency>()
                    if (moduleDeps.isEmpty()) return@withDependencies
                    val gameLibraryExcludes = ext.artifacts(environment.environmentId)
                        .get().libraries.get()
                        .filter { dep -> environment.libraryFilters.all { it(dep) } }
                        .mapNotNull { dep ->
                            dep.group?.let { group -> group to dep.name }
                        }
                    moduleDeps.forEach { modDep ->
                        gameLibraryExcludes.forEach { (group, module) ->
                            modDep.exclude(mapOf("group" to group, "module" to module))
                        }
                    }
                }
            }
        }
        // resolvable — the production-jar JiJ post-pass resolves its
        // artifacts for embedded file+coordinate pairs
        project.configurations.maybeCreate(
            environment.includeConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    }

    /**
     * Attach this environment's Minecraft artifact and resolved libraries
     * to the bound source set's compile/runtime classpaths.
     *
     * The MC artifact rides the local-repo stub mechanism: a lazily-added
     * `unimined.generated` module dep on [MinecraftEnvironment.minecraftConfigurationName]
     * resolves through the per-environment resolvable view
     * ([MinecraftEnvironment.minecraftRawViewName]), whose `artifactType`
     * request triggers [ProductArtifactTransform] — the real jar
     * materializes at resolution time, cacheably, including during IDE
     * sync. The transform host may additionally wire a dev-namespace view
     * ([MinecraftExtension.devViewConfigurations]); the classpath
     * contribution picks it up lazily so plugin ordering does not matter.
     */
    private fun wireClasspath(environment: MinecraftEnvironment, sourceSet: SourceSet) {
        ext.stubRepo.ensure()
        val environmentId = environment.environmentId

        val minecraftBucket = project.configurations.maybeCreate(
            environment.minecraftConfigurationName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        // added lazily — the coordinate needs the script-final spec and
        // the marker stub is (re)written alongside the dependency
        minecraftBucket.withDependencies { deps ->
            // versionless envs (shared-code `main`) are inert — no
            // product, no stub jar
            if (!environment.version.isPresent) return@withDependencies
            deps.add(project.dependencies.create(ext.stubRepo.stubDependencyNotation(environment)))
            // obfuscated aux artifacts (e.g. the Realms jar — issue #27)
            // remap into the same views as the MC jar. They must enter
            // as typed artifacts — a plain `jar`-typed file dep would
            // be claimed by ProductArtifactTransform (which produces
            // the MC product for any jar input) and the env's raw type
            // would pull the product-scoped jar processors. The
            // dedicated aux artifactType takes a remap-only channel:
            // aux->raw passes through, aux->dev/prod remap names only.
            ext.artifacts(environmentId).get()
                .obfuscatedAuxArtifacts.orNull.orEmpty()
                .forEachIndexed { i, file ->
                    val notation = ext.stubRepo.auxDependencyNotation(
                        environment, i, file.get().asFile.toPath()
                    )
                    val dep = project.dependencies.create(notation)
                        as org.gradle.api.artifacts.ExternalModuleDependency
                    dep.artifact { a ->
                        a.name = notation.split(':')[1]
                        a.type = ext.auxArtifactType(environmentId)
                        a.extension = "jar"
                    }
                    deps.add(dep)
                }
        }

        val librariesBucket = project.configurations.maybeCreate(
            environment.minecraftLibrariesConfigurationName
        ).apply {
            isCanBeConsumed = false
            // resolvable — serves both extendsFrom'd classpath deps and
            // FileCollection inputs (remap analysis classpaths)
            isCanBeResolved = true
        }
        librariesBucket.withDependencies { deps ->
            if (!environment.version.isPresent) return@withDependencies
            deps.addAll(
                ext.artifacts(environmentId).get().libraries.get()
                    .filter { dep -> environment.libraryFilters.all { it(dep) } }
            )
            // 1.x parity: the dev jar's annotation surface is jetbrains
            // (RemapStepSpec.jsr305ToJetbrains, default on) but modern
            // MC ships neither annotation lib in its version manifest —
            // the stub repo serves it for repo-less consumers
            deps.add(project.dependencies.create(
                "org.jetbrains:annotations:${LocalStubRepo.JETBRAINS_ANNOTATIONS_VERSION}"
            ))
            // `lwjgl3 {}` swap — lwjgl3 jars land on the same classpath
            // conf the version-JSON libraries feed (the lwjgl2 drop
            // rides the shared libraryCoordFilters pipeline)
            if (environment.lwjgl3.enabled.get()) {
                environment.lwjgl3.jarCoords().forEach {
                    deps.add(project.dependencies.create(it))
                }
            }
        }
        // the provider-declared lib set only — no user/transform
        // contributions (client-extra & co carry the raw-artifact
        // back-edge that re-enters transform param isolation); feeds
        // the merge strip's bundled-library membership
        val declaredLibraries = project.configurations.create(
            environment.minecraftDeclaredLibrariesConfigurationName
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
        }
        declaredLibraries.withDependencies { deps ->
            if (!environment.version.isPresent) return@withDependencies
            deps.addAll(
                ext.artifacts(environmentId).get().libraries.get()
                    .filter { dep -> environment.libraryFilters.all { it(dep) } }
            )
        }
        project.configurations.named(sourceSet.compileClasspathConfigurationName) { conf ->
            conf.extendsFrom(librariesBucket)
            conf.withDependencies {
                // the java plugin derives `jvm.version` from the
                // compile task's emit level (`options.release`/target)
                // — mods may emit Java 8 bytecode against a newer
                // dependency floor, and variant matching then rejects
                // the env's own MC/loader libraries. Re-stamp with the
                // level the compile actually reads class files at
                conf.attributes.attribute(
                    TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                    environment.compileJavaVersion.get()
                )
            }
        }
        project.configurations.named(sourceSet.runtimeClasspathConfigurationName) { conf ->
            conf.extendsFrom(librariesBucket)
            conf.withDependencies {
                // runtime resolution is bound by the env's launch floor
                conf.attributes.attribute(
                    TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                    environment.requiredJavaVersion.get()
                )
            }
        }

        registerMinecraftTransform(environment)

        val rawView = project.configurations.create(environment.minecraftRawViewName) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(minecraftBucket)
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ext.rawArtifactType(environmentId)
            )
        }
        ext.rawViewConfigurations[environmentId] = rawView

        // MC in the production namespace — prod launches and mod-view
        // remap analysis consume it (the latter so overrides of mapped
        // MC members propagate); resolves to the raw artifact via a
        // namespace-hop transform only when the prod namespace differs
        // from the source namespace
        val prodView = project.configurations.create(environment.minecraftProdViewName) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(minecraftBucket)
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ext.prodArtifactType(environmentId)
            )
        }
        ext.prodViewConfigurations[environmentId] = prodView

        // Picks the raw view when the provider reports an identity pipeline
        // or the resolved dev namespace equals the source namespace, so
        // identity environments never materialize a duplicate dev jar —
        // unless the env's JAR pipeline carries env-scoped work (jar
        // processors, contributed/declared steps): the raw→dev transform
        // is the only host for it, so the dev artifact must materialize.
        //
        // Provider shape matters under the configuration cache:
        // `fileCollection.from(provider { conf })` (a CallableProvider
        // yielding a Configuration) resolves to EMPTY on CC-restore
        // runs — Gradle loses the conf's resolution link and the view's
        // transform outputs never materialize. The working idiom is a
        // provider chain of domain-object providers: a Boolean decision
        // provider (its snapshot is stable — the namespace mode can't
        // change between runs) flat-mapped onto `configurations.named`
        // branches so each branch stays a first-class conf reference
        // resolved at execution.
        val useRawView = providers.provider {
            if (ext.devViewConfigurations[environmentId] == null) return@provider true
            val providerArtifacts = ext.artifacts(environmentId).get()
            if (ext.materializeDevArtifact[environmentId]?.orNull != true) {
                if (providerArtifacts.namespaceMode.get() == NamespaceMode.IDENTITY) {
                    return@provider true
                }
                val dev = ext.devNamespace(environmentId).orNull
                val source = ext.sourceNamespace(environmentId).orNull
                dev != null && dev == source
            } else false
        }
        // devView's Configuration name is only stable after transform
        // wiring registers it — resolve the name lazily inside a
        // provider so the pick still works when this binding runs first
        val devViewName = providers.provider {
            ext.devViewConfigurations[environmentId]?.name ?: rawView.name
        }
        val contribution = objects.fileCollection().from(
            useRawView.flatMap { useRaw ->
                devViewName.flatMap { name ->
                    project.configurations.named(if (useRaw) rawView.name else name)
                }
            }
        )
        sourceSet.compileClasspath += contribution
        // dev-run MC view — the compile pick by default; loader wiring
        // may aim dev launches at a different namespace artifact
        // (`devRunViewName` — FG2 `srgRuntime` puts the searge/prod
        // view on the launch classpath). Read lazily so post-binding
        // wiring still takes effect
        sourceSet.runtimeClasspath += objects.fileCollection().from(
            useRawView.flatMap { useRaw ->
                providers.provider { environment.devRunViewName }
                    .flatMap { runView ->
                        if (runView.isNotEmpty()) {
                            project.configurations.named(runView)
                        } else devViewName.flatMap { name ->
                            project.configurations.named(
                                if (useRaw) rawView.name else name)
                        }
                    }
            }
        )

        val extraDependencies = objects.fileCollection().from(providers.provider {
            ext.artifacts(environmentId).get().extraDependencies
        })
        sourceSet.compileClasspath += extraDependencies
        sourceSet.runtimeClasspath += extraDependencies

        sourceSet.java.srcDir(objects.fileCollection().from(providers.provider {
            ext.artifacts(environmentId).get().extraCompileSources
        }))
    }

    private fun registerMinecraftTransform(environment: MinecraftEnvironment) {
        val environmentId = environment.environmentId
        if (!registeredTransformEnvironments.add(environmentId)) return
        val cacheSettings = ProviderCacheSettings.from(project)
        // downloads + version/side-keyed merge intermediates are
        // content-identical across projects — global cache (1.x/loom
        // model); project-stamped artifacts stay under .gradle
        val cacheDir = UniminedCache.global(project).resolve("native-cache")

        project.dependencies.registerTransform(ProductArtifactTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ext.rawArtifactType(environmentId)
            )
            t.parameters { p ->
                p.environmentId.set(environmentId)
                p.version.set(environment.version)
                p.side.set(environment.side.map { it.name })
                p.artifactKey.set(environment.side.map { ext.sideKey(it).name })
                p.artifactBaseName.set(environment.artifactBaseName)
                environment.provider.orNull?.let { p.producerName.set(it) }
                p.loaderName.set(environment.loaderSpec.map { it.name })
                p.loaderVersion.set(environment.loaderSpec.map { it.version })
                p.product.set(environment.product)
                p.declaredSource.set(providers.provider {
                    environment.legacyDist.declaration()
                })
                p.cacheDir.set(cacheDir.toString())
                p.offline.set(cacheSettings.offline)
                p.refreshUniminedDownloads.set(cacheSettings.refreshUniminedDownloads)
                p.rebuildUniminedArtifacts.set(cacheSettings.rebuildUniminedArtifacts)
                p.artifactFormatVersion.set(providers.provider {
                    val spec = RuntimeProductSpec(
                        environmentId = environmentId,
                        sourceSetName = environmentId,
                        version = RuntimeVersionSpec(environment.version.get()),
                        side = environment.side.get(),
                        loader = null,
                        product = environment.product.get(),
                        // carry the declaration so declared-source envs
                        // price the producer that will actually run
                        // (legacydist/devbundle), not the native floor
                        declaredSource = environment.legacyDist.declaration()
                    )
                    ProductArtifactProducerRegistry()
                        .select(spec, environment.provider.orNull)
                        .artifactFormatVersion
                })
                cacheSettings.metadataCacheTtl?.let { p.metadataCacheTtl.set(it.toString()) }
                // merge strip controls — the provider's *declared*
                // library set (version-json libs), never the
                // `minecraftLibraries` configuration: that config
                // transitively contains providers resolving the raw
                // artifact view (forge's client-extra), which would
                // re-enter this very transform's param isolation
                p.libraryJars.from(
                    project.configurations.getByName(
                        environment.minecraftDeclaredLibrariesConfigurationName
                    )
                )
                p.mergeIncludeGlobs.set(environment.mergeIncludeGlobs)
                p.mergeExcludeGlobs.set(environment.mergeExcludeGlobs)
                p.stripBundledServerLibraries.set(environment.stripBundledServerLibraries)
                // orElse — loader wirings contribute via addAll(provider)
                // and an absent contribution would otherwise leave the
                // isolated param with no configured value (2.0.0 defect)
                p.sideMarkers.set(environment.sideMarkers.orElse(emptyList()))
                // orElse — same absent-provider poisoning as
                // sideMarkers: loader wirings contribute via
                // addAll(provider) and an absent value would
                // otherwise leave the param unset
                p.sideJarPatches.set(
                    environment.sideJarPatches.orElse(emptyList()))
                // split-side merged env: remap each side into the
                // shared (mod-source) namespace before merging — the
                // raw merge can't pair disjoint obf namespaces.
                // splitOfficial resolves lazily — channel declarations
                // may run after this registration
                p.mergeNamespace.set(providers.provider {
                    if (environment.mappingsSpec.splitOfficial) {
                        ext.modSourceNamespaces(environmentId).orNull?.let { ns ->
                            ns.singleOrNull() ?: project.failProblem(
                                "env/split-official-namespaces",
                                "unimined: environment '$environmentId' is " +
                                "split-official but has ${ns.size} mod source " +
                                "namespaces",
                                "the merged remap needs exactly one; declare " +
                                "remap.remappedViews for mixed inputs"
                            )
                        } ?: project.failProblem(
                            "env/mod-source-namespace",
                            "unimined: environment '$environmentId' has no mod " +
                            "source namespace",
                            "declare a loader or set modSourceNamespaces"
                        )
                    } else null
                })
                p.mergeFromNamespaces.set(providers.provider {
                    if (environment.mappingsSpec.splitOfficial) {
                        environment.mappingsSpec.splitOfficialNamespaces
                    } else null
                })
                p.mergeMappingsFile.set(
                    ext.project.layout.file(providers.provider {
                        if (environment.mappingsSpec.splitOfficial) {
                            ext.mappingBundleFile(
                                environmentId,
                                ext.artifacts(environmentId).map {
                                    it.mappings.orNull?.namespaces?.firstOrNull()?.name
                                        ?: project.failProblem(
                                            "env/mapping-tree",
                                            "unimined: environment " +
                                            "'$environmentId' produced no " +
                                            "mapping tree"
                                        )
                                },
                                ext.modSourceNamespaces(environmentId)
                                    .map { it.single() }
                            ).orNull?.asFile
                        } else null
                    }.map { it!! })
                )
            }
        }
    }
}
