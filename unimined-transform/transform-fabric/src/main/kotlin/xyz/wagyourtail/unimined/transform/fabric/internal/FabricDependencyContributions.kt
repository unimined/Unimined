package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.commonskt.utils.capitalized
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Dependency contributions for fabric-family environments: LWJGL
 * substitutions and library overlays, the intermediary mapping jar,
 * and the loader jar + its embedded libraries. Every contribution
 * self-gates on `loaderSpec`/resolved providers so loaderless envs
 * stay inert — see [FabricEnvironmentWiring].
 */
internal object FabricDependencyContributions {

    /** LWJGL2-era loaders need the patched LWJGL2 builds to run on
     *  modern JVMs (1.x `replaceLwjglVersion`): `legacyfabric`/`babric`/
     *  `ornithe` swap `org.lwjgl:*` onto `2.9.4+legacyfabric.8` (the
     *  1.x LegacyFabric default). Modern `fabric`/`quilt` run LWJGL3
     *  and get no swap. (1.x also had a BTA `flint` swap of only the
     *  `org.lwjgl.lwjgl` artifact — removed with the loader.) */
    fun contributeLibrarySubstitutions(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        val descriptor = environment.loaderSpec.orNull?.name
            ?.let { LoaderRegistry.resolveBuiltin(it) }
            ?.takeIf { it.family == "fabric" }
        if (descriptor == null) return
        descriptor.capabilities["replaceLwjgl"]?.let { target ->
            val match = descriptor.capabilities["replaceLwjglMatch"] ?: "org.lwjgl"
            environment.librarySubstitutions += match to target
        }
        // 1.x `FabricLikeMinecraftTransformer` dropped org.ow2.asm:asm-all
        // from MC's libraries — fabric-family loaders ship their own asm
        // and the duplicate lands a second ASM on the launch classpath
        environment.libraryFilters += { dep ->
            !(dep.group == "org.ow2.asm" && dep.name == "asm-all")
        }
        descriptor.capabilities["mergeIncludeGlobs"]?.let { globs ->
            environment.mergeIncludeGlobs.addAll(globs.split(' '))
        }
        // `manifestAttr.<Key>` capabilities → env manifest channel —
        // 1.x `jarTask.manifest` attrs ride the packaging merge file
        descriptor.capabilities.forEach { (k, v) ->
            if (k.startsWith("manifestAttr.")) {
                environment.manifestAttributes.put(
                    k.removePrefix("manifestAttr."), v)
            }
        }
        // merged-env side markers — every fabric-family loader ships
        // `net.fabricmc.api.Environment`; the merge stamps it on
        // single-side classes/members (1.x FabricLike `merger`)
        environment.sideMarkers.add(
            xyz.wagyourtail.unimined.minecraft.merge.JarMerger
                .SideMarker.FABRIC
        )
    }

    /** Contribute the loader descriptor's gen-dependent library overlay,
     *  when it declares one. This remains post-DSL: the overlay generation
     *  follows `mappingsSpec.ornitheGenVersion`, which user mapping blocks
     *  commonly configure after the loader declaration. */
    fun contributeLibraryOverlayCapability(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        val descriptor = environment.loaderSpec.orNull?.name
            ?.let { LoaderRegistry.resolveBuiltin(it) }
            ?.takeIf { it.family == "fabric" }
            ?: return
        descriptor.capabilities["libraryOverlayMeta"]?.let { meta ->
            // `unimined.ornitheMeta` overrides the descriptor's meta
            // base — mirrors, airgapped caches, and tests (`file:`
            // URLs work); shared by OrnitheAutoWiring's index lookup
            contributeLibraryOverlay(
                project, environment,
                (project.findProperty("unimined.ornitheMeta") as? String)
                    ?: meta
            )
        }
    }

    /** Ornithe-era loaders backport slf4j/log4j + modernized deps onto
     *  old MC via a per-generation overlay — register the
     *  `gen{n}/libraries` overlay URL matching the environment's
     *  configured `ornitheGenVersion` (1.x never hard-coded dep lists;
     *  the plan keeps the same shape). Drives off the descriptor's
     *  `libraryOverlayMeta` capability, not the loader name. */
    fun contributeLibraryOverlay(
        project: Project,
        environment: MinecraftEnvironment,
        meta: String
    ) {
        // the env's configured gen — 1.x parity
        // (`provider.mappings.ornitheGenVersion`): the mappings actually
        // composed determine both the overlay's library set and the
        // manifest's generation claim; the meta index's
        // `stableIntermediaryGeneration` is the *ecosystem's* current
        // gen, not this environment's
        val gen = environment.mappingsSpec.ornitheGenVersion
        environment.libraryOverlays += "$meta/v3/versions/gen$gen/libraries"
        // 1.x OrnitheFabricMinecraftTransformer's `Calamus-Generation`
        // manifest attr
        environment.manifestAttributes.put("Calamus-Generation", gen.toString())
    }

    /**
     * Prepend `official -> intermediary` to the environment's mapping
     * chain when a fabric loader is declared — user mappings (e.g. yarn)
     * then compose `intermediary -> named` on top, and a bare fabric
     * environment still lands on the `intermediary` dev namespace.
     * The raw `intermediary` jar is contributed as the source; the
     * mapping materializer unwraps its `mappings/mappings.tiny`.
     */
    fun contributeIntermediaryMappings(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val version = environment.version
        val dependencies = project.dependencies

        // bound fires before the environment's DSL populates
        // loaderSpec, so the channel check runs lazily inside
        // effectiveSources — descriptor rows with
        // upstreamMappingChannel route through the env's own
        // mapping DSL, captured as defaultSources
        environment.mappingsSpec.deferredDefaultSources += {
            // unobfuscated mc (26.x) has no obfuscation hop to map —
            // dev namespace is official, skip the whole intermediary hop
            if (!environment.obfuscated.get() ||
                environment.mappingsSpec.customIntermediaries.get()
            ) emptyList()
            else {
                val spec = environment.loaderSpec.orNull
                val descriptor = spec?.name
                    ?.let { LoaderRegistry.resolveBuiltin(it) }
                    ?.takeIf { it.family == "fabric" }
                when (val channel = descriptor?.upstreamMappingChannel) {
                    null -> emptyList()
                    "calamus" -> collectDeclared { calamus() }
                    else -> throw org.gradle.api.GradleException(
                        "unknown upstreamMappingChannel '$channel'"
                    )
                }
            }
        }
        val intermediary = project.configurations.create(
            "fabricIntermediary${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.withDependencies { deps ->
                if (!environment.obfuscated.get() ||
                    environment.mappingsSpec.customIntermediaries.get()
                ) return@withDependencies
                val descriptor = loaderSpec.orNull?.name
                    ?.let { LoaderRegistry.resolveBuiltin(it) }
                    ?.takeIf { it.family == "fabric" }
                if (descriptor?.upstreamMappingChannel == null) {
                    val coord = descriptor?.intermediaryCoords?.invoke(version.get())
                    if (coord != null) {
                        deps.add(dependencies.create(coord))
                    }
                }
            }
        }
        val intermediaryJar = project.files(intermediary)

        val files = project.files(project.provider {
            val spec = loaderSpec.orNull
            val descriptor = spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }
            // unobfuscated mc needs no intermediary jar at all
            if (!environment.obfuscated.get() ||
                environment.mappingsSpec.customIntermediaries.get() ||
                descriptor?.family != "fabric" ||
                descriptor.upstreamMappingChannel != null) {
                // upstreamMappingChannel rows contribute Maven sources via
                // defaultSources instead — no coord jar to resolve here
                emptyList()
            } else {
                listOf(intermediaryJar.singleFile)
            }
        })
        environment.mappingsSpec.defaultSources += MappingsSpecImpl.Source.File(files)

        // split-era loaders (babric): the upstream artifact carries
        // per-side obf columns on a bare File source, so no channel
        // declaration ever marks the spec — declare it here so the
        // merged env remaps each side into the shared column first.
        // loaderSpec is unpopulated at bound time — resolve it after
        // DSL eval, still ahead of the transform's lazy providers
        project.afterDslEval {
            environment.loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
                ?.takeIf { it.family == "fabric" }
                ?.splitMerge?.let {
                    environment.mappingsSpec.markSplitOfficial(
                        it.fromNamespaces, it.mergeNamespace
                    )
                }
        }
    }

    /** The loader jar is an intermediary-named mod dependency — it rides
     *  the environment's remapped mod view onto the dev classpath. */
    fun contributeLoaderDependency(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val dependencies = project.dependencies

        val loader = project.configurations.create(
            "fabricLoader${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                val descriptor = spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }
                    ?.takeIf { it.family == "fabric" }
                if (descriptor != null) {
                    deps.add(
                        dependencies.create(
                            descriptor.loaderCoords(
                                environment.version.get(), spec!!.version
                            )
                        )
                    )
                }
            }
        }
        // The env's loader is platform, not a mod — keep it (and known
        // fabric-family platform coords) out of the mod buckets. Mod
        // jars like OSL's declare fabric-loader transitives; the
        // remapped mod view resolves the bucket alone where the env
        // loader can't win conflict resolution, so the transitive
        // surfaces as a *transformed file dep* next to the real loader
        // jar → duplicate loader classes at launch.
        listOf(
            environment.modApiConfigurationName,
            environment.modImplementationConfigurationName,
            environment.modCompileOnlyConfigurationName,
            environment.modCompileOnlyApiConfigurationName,
            environment.modRuntimeOnlyConfigurationName,
            environment.modLocalRuntimeConfigurationName
        ).forEach { bucket ->
            project.configurations.matching { it.name == bucket }.all { conf ->
                conf.withDependencies {
                    // every declared fabric-family loader — merged
                    // multi-loader envs keep secondary loaders' coords
                    // out of the mod buckets too, so dep transitives
                    // can't shadow the merged secondary's version
                    val platforms = mutableSetOf(
                        "net.fabricmc:fabric-loader",
                        "org.quiltmc:quilt-loader"
                    )
                    var fabric = false
                    environment.loaderSpecs.forEach { spec ->
                        val descriptor = LoaderRegistry.resolveBuiltin(spec.name)
                            ?.takeIf { it.family == "fabric" }
                            ?: return@forEach
                        fabric = true
                        descriptor.loaderCoords(
                            environment.version.get(), spec.version
                        ).split(":").takeIf { it.size >= 2 }
                            ?.let { platforms += "${it[0]}:${it[1]}" }
                    }
                    if (!fabric) return@withDependencies
                    platforms.forEach { ga ->
                        val (g, m) = ga.split(":", limit = 2)
                        conf.exclude(mapOf("group" to g, "module" to m))
                    }
                }
            }
        }
        // loader's own runtime libraries — the pom declares none; the jar
        // embeds fabric-installer.json listing them (asm, sponge-mixin)
        val loaderFiles = project.files(loader)
        val loaderLibraries = project.configurations.create(
            "fabricLoaderLibraries${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                if (spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }?.family != "fabric")
                    return@withDependencies
                val jar = loaderFiles.singleFile
                FabricModAnalysis.loaderLibraries(
                    jar.toPath(),
                    environment.side.orNull?.name?.lowercase()
                ).forEach {
                    deps.add(dependencies.create(it))
                }
            }
        }
        // merged multi-loader envs: a *secondary* fabric-family loader's
        // embedded installer libraries (sponge-mixin for @Mixin, asm, …)
        // belong on the compile classpath too — the generic mergedLoader
        // bucket carries the secondary's jar; this carries the libs its
        // fabric/quilt installer.json declares, mirroring the primary's
        // fabricLoaderLibraries. Compile-only: run semantics stay with
        // the primary loader.
        val mergedLoaderLibraries = project.configurations.create(
            "fabricMergedLoaderLibraries${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                environment.loaderSpecs.drop(1).forEach { spec ->
                    val descriptor = LoaderRegistry.resolveBuiltin(spec.name)
                        ?.takeIf { it.family == "fabric" } ?: return@forEach
                    // resolve the secondary's loader jar standalone —
                    // mergedLoader's graph also carries non-fabric
                    // secondaries we must not re-read here
                    val jarConf = project.configurations.detachedConfiguration(
                        dependencies.create(
                            descriptor.loaderCoords(
                                environment.version.get(), spec.version
                            )
                        )
                    )
                    jarConf.isTransitive = false
                    jarConf.resolve().forEach { jar ->
                        FabricModAnalysis.loaderLibraries(
                            jar.toPath(),
                            environment.side.orNull?.name?.lowercase()
                        ).forEach { deps.add(dependencies.create(it)) }
                    }
                }
            }
        }
        // dep-jar installer libraries — loom's `ArtifactMetadata`/
        // `InstallerData` parity: ANY fabric/quilt mod jar on the mod
        // views may embed `fabric-installer.json`/`quilt_installer.json`
        // declaring extra runtime libs (loom reads `common` +
        // `development`, non-transitive). Scanned off the raw
        // (source-ns) direct views at resolution time.
        val depInstallerLibraries = project.configurations.create(
            "fabricDepInstallerLibraries${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                if (spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }?.family != "fabric")
                    return@withDependencies
                listOf(
                    "${environment.modApiConfigurationName}Direct",
                    "${environment.modImplementationConfigurationName}Direct",
                    "${environment.modCompileOnlyConfigurationName}Direct",
                    "${environment.modCompileOnlyApiConfigurationName}Direct",
                    "${environment.modRuntimeOnlyConfigurationName}Direct",
                    "${environment.modLocalRuntimeConfigurationName}Direct"
                ).flatMap { name ->
                    project.configurations.findByName(name)?.files ?: emptySet()
                }.forEach { jar ->
                    FabricModAnalysis.depInstallerLibraries(jar.toPath())
                        ?.forEach { lib ->
                            val dep = dependencies.create(lib.name)
                            // loom: "Match the launcher in not being
                            // transitive"
                            (dep as? org.gradle.api.artifacts
                                .ExternalModuleDependency)
                                ?.isTransitive = false
                            deps.add(dep)
                            lib.url?.let { url ->
                                project.reportProblem(
                                    "fabric/installer-lib-repo",
                                    "unimined: ${jar.name}: installer lib " +
                                            "'${lib.name}' declares repo '$url'",
                                    "declare it in repositories { } if " +
                                            "resolution fails"
                                )
                            }
                        }
                }
            }
        }
        // loom parity: loader + its embedded libraries are
        // namespace-agnostic — they ride the source-set classpaths
        // raw so the remapped mod view never transforms them
        val sourceSet = project.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets
            .findByName(environment.environmentId) ?: return
        listOf(
            sourceSet.compileClasspathConfigurationName,
            sourceSet.runtimeClasspathConfigurationName,
        ).forEach { name ->
            project.configurations.named(name) { conf ->
                conf.extendsFrom(loader, loaderLibraries, depInstallerLibraries)
            }
        }
        // prod runs bypass `sourceSet.runtimeClasspath` — the env
        // libraries channel is the one extended onto every launch
        // classpath (dev compile+runtime, prod run, remap analysis).
        // 1.x `provider.minecraftLibraries.extendsFrom(fabric)` parity
        project.configurations.named(
            environment.minecraftLibrariesConfigurationName
        ) { conf ->
            conf.extendsFrom(loader, loaderLibraries, depInstallerLibraries)
        }
        // merged secondaries' libs are compile-only — the env runs
        // under the primary loader
        project.configurations.named(sourceSet.compileClasspathConfigurationName) {
            it.extendsFrom(mergedLoaderLibraries)
        }
        // canonical-name `mappings/mappings.tiny` — the loader's
        // MappingConfiguration only accepts `official`,
        // `clientOfficial`/`serverOfficial`, `intermediary`, `named`
        // columns, so the merged tree is re-emitted relabeled (see
        // exportLoaderMappings). On the runtime classpath it drives the
        // runtime namespace, the classTweaker header check, and the
        // loader's MappingResolver. Unobfuscated envs run `official`
        // and need no relabel.
        val loaderMappingsDir = UniminedCache.global(project)
            .resolve("fabric/loader-mappings")
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val mappingsTree = minecraft
            .artifacts(environment.environmentId).flatMap { it.mappings }
        val devNs = minecraft.devNamespace(environment.environmentId)
        val loaderMappings = project.provider {
            val spec = loaderSpec.orNull
            val descriptor = spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }
                ?.takeIf { it.family == "fabric" }
            if (descriptor == null || !environment.obfuscated.get()) {
                emptyList()
            } else {
                listOf(
                    exportLoaderMappings(
                        mappingsTree.get(),
                        devNs.orNull,
                        descriptor.prodNamespace,
                        environment.environmentId,
                        loaderMappingsDir
                    ).toFile()
                )
            }
        }
        // file dep, not a sourceSet append: prod runs bypass
        // `sourceSet.runtimeClasspath` but still need
        // `mappings/mappings.tiny` — the TINY_JAR container is a real
        // jar, safe for every minecraftLibraries consumer (launch
        // classpaths, remap analysis, tool `-e=` rows)
        project.configurations.named(
            environment.minecraftLibrariesConfigurationName
        ) { conf ->
            conf.dependencies.add(
                project.dependencies.create(project.files(loaderMappings))
            )
        }
    }
}
