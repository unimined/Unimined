package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.commonskt.utils.capitalized
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.fabric.processor.ModJavadocJarProcessor
import xyz.wagyourtail.unimined.transform.fabric.processor.fabricSpecContext
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.produceCached
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.transform.internal.authoredResourceDirs
import java.io.File
import org.gradle.api.tasks.SourceSetContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The environment-effective production namespace — descriptors may
 * declare a `prodNamespaceGenTemplate` capability (ornithe's
 * `calamusGen%d`) which wins when the env's `ornitheGenVersion` is
 * gen >= 2, else the descriptor's static `prodNamespace`.
 */
internal fun LoaderDescriptor.effectiveProdNamespace(
    environment: MinecraftEnvironment
): String {
    val gen = environment.mappingsSpec.ornitheGenVersion
    return capabilities["prodNamespaceGenTemplate"]
        ?.takeIf { gen >= 2 }
        ?.format(gen)
        ?: prodNamespace
}

/**
 * Per-environment fabric wiring. Binding callbacks can run before the
 * build script assigns `loader(...)`, so every contribution checks the
 * loader spec lazily — inside dependency materialization or provider
 * queries — and providers capture only spec properties, file
 * collections, and paths so they survive the configuration cache.
 */
internal object FabricEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        // loaderless envs (e.g. `main` under flat multiloader) carry
        // no loader — contributing loader-gated sources/steps would
        // leave loaderSpec-bound providers unset at resolve time
        // no loaderSpec gate at wire time — envs bind at declaration,
        // before `loader(...)` lands; contributions self-gate on the
        // descriptor/resolved providers (empty on non-fabric envs)
        // Loader-derived library/merge state must be present before any
        // product resolution snapshots spec(). Binding happens before the
        // build script's loader(...) call, so afterDslEval is too late:
        // user/plugin wiring may force an early resolution (for example
        // through env.obfuscated) and the memoized ProductArtifacts would
        // otherwise keep the pre-substitution library set forever.
        environment.onPrimaryLoaderDeclared {
            FabricDependencyContributions.contributeLibrarySubstitutions(
                project, environment)
        }
        // fabric's MC artifact is the vanilla jar — the transform layers
        // loader behavior on top; convention lets users override, and the
        // convention itself only applies when the loader is fabric
        environment.provider.convention(
            environment.loaderSpec.map {
                if (LoaderRegistry.resolveBuiltin(it.name)?.family == "fabric") "native" else null
            }
        )
        // gen-aware prod namespaces (ornithe's `calamusGen<N>`): the
        // global `loaderModSourceNamespaces` registration holds the
        // descriptor default — the env's effective prod column follows
        // its configured mapping gen. The descriptor is resolved
        // eagerly here (not inside a provider) because ServiceLoader
        // lookups fail under the instrumented classloader used during
        // dependency resolution. A user `set()` still wins over the
        // convention.
        project.afterDslEval {
            val descriptor = environment.loaderSpec.orNull?.name
                ?.let { LoaderRegistry().resolve(it) }
                ?.takeIf { it.family == "fabric" }
            if (descriptor != null) {
                environment.modSourceNamespaces.convention(
                    project.provider {
                        // unobfuscated mc (26.x) has no intermediary
                        // column — prod runs at mojmap, same as forge's
                        // `obfuscated` gate in its convention
                        if (!environment.obfuscated.get()) listOf("mojmap")
                        else listOf(descriptor.effectiveProdNamespace(environment))
                    }
                )
            }
            FabricDependencyContributions.contributeLibraryOverlayCapability(
                it, environment)
        }
        FabricDependencyContributions.contributeIntermediaryMappings(project, environment)
        FabricDependencyContributions.contributeLoaderDependency(project, environment)
        contributeAccessSteps(project, environment)
        contributeRunMutator(project, environment)
        contributeJarProcessors(project, environment)
        OrnitheAutoWiring.contribute(project, environment)
    }




    /**
     * Contribute the late JAR-surface steps: `apply-class-tweaker` merges
     * declared AW/CTs into the dev artifact plus the `transitive-*` rules
     * from classTweakers extracted out of compile-classpath mod
     * dependencies, and `remap-access` rebases AW/AT/CT resources embedded
     * in mod jars onto the dev namespace.
     */
    private fun contributeAccessSteps(project: Project, environment: MinecraftEnvironment) {
        val objects: ObjectFactory = project.objects
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentSpec = remap.sourceSet(environment.environmentId)
        // resolved dev ns — the raw `environment.devNamespace`
        // property is unset in the common case, and the absent value
        // then fails transform-param isolation outright
        val devNamespace = project.extensions
            .getByType(MinecraftExtension::class.java)
            .devNamespace(environment.environmentId)

        // compile-visible mod buckets only (Loom's MOD_COMPILE_ENTRIES
        // where compileClasspath()): runtime-only mods' AWs are applied by
        // the loader itself at runtime, not baked into the compile artifact
        val modJars = project.configurations.create(
            "fabricModAnalysis${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(
                project.configurations.getByName(environment.modApiConfigurationName),
                project.configurations.getByName(environment.modImplementationConfigurationName),
                project.configurations.getByName(environment.modCompileOnlyConfigurationName),
                project.configurations.getByName(environment.modCompileOnlyApiConfigurationName),
                // loom parity: the loader's embedded libraries ride the
                // same view onto the mod classpath. The loader jar
                // itself joins below as a *file* dep — the mod buckets
                // exclude loader coords so mod transitives can't
                // duplicate the env loader, and conf excludes filter
                // module coords across an extending conf's whole
                // graph, so a module dep here would be silently
                // filtered out of this view
                project.configurations.getByName(
                    "fabricLoaderLibraries${environment.environmentId.capitalized()}"
                )
            )
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
        }
        project.dependencies.add(
            modJars.name,
            project.files(
                project.configurations.getByName(
                    "fabricLoader${environment.environmentId.capitalized()}"
                )
            )
        )
        val modJarFiles = project.files(modJars)
        // per-mod-jar keyed extraction — global cache
        val ctCacheDir = UniminedCache.global(project).resolve("fabric/ct")
        // every dep-declared classTweaker file, unfiltered — the
        // compile-artifact input filters to transitive rules below, but
        // the analysis model needs the complete rule set. fmj
        // `custom.*injected_interfaces` declarations synthesize an
        // equivalent transitive CT per jar (loom parity: those keys are
        // how published mods declare interface injection). Access docs
        // and II synthesis gate on independent flags — loom's
        // `transitiveAccessWideners` vs `enableDependencyInterfaceInjection`.
        val extractedCts = project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family != "fabric" ||
                !environment.transitiveAccessWideners.get()
            ) {
                emptyList()
            } else {
                modJarFiles.files.flatMap { jar ->
                    FabricModAnalysis.extractClassTweakers(
                        jar.toPath(),
                        ctCacheDir.resolve(UniminedCache.artifactKey(jar.toPath()))
                    )
                }
            }
        }
        val extractedIis = project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family != "fabric" ||
                !environment.dependencyInterfaceInjection.get()
            ) {
                emptyList()
            } else {
                modJarFiles.files.flatMap { jar ->
                    synthesizeInjectedInterfacesCt(
                        jar,
                        ctCacheDir.resolve(UniminedCache.artifactKey(jar.toPath())),
                        descriptor.effectiveProdNamespace(environment)
                    )
                }
            }
        }

        // the project's own mod-json `*injected_interfaces` — a local
        // declaration in dev namespace (the project's own mappings,
        // like user-authored AWs). Applies via the own channel; dep
        // jars' II stays on `dependencyClassTweakerFiles` (loom parity:
        // `RemappedSpecContext` scans local mods too). Project state,
        // so env-scoped under the project cache rather than `ctCacheDir`
        val localCtDir = UniminedCache.project(project)
            .resolve("fabric/ct/${environment.environmentId}")
        val localIis = project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            val name = descriptor?.takeIf { it.family == "fabric" }
                ?.metadata?.fileName
            if (name == null ||
                !environment.dependencyInterfaceInjection.get()
            ) {
                emptyList()
            } else {
                (project.extensions
                    .getByType(SourceSetContainer::class.java)
                    .findByName(environment.environmentId)
                    ?.let { authoredResourceDirs(project, it) }.orEmpty())
                    .mapNotNull { it.resolve(name).takeIf(File::isFile) }
                    .flatMap {
                    synthesizeLocalInjectedInterfacesCt(
                        it, localCtDir, devNamespace.get()
                    )
                }
            }
        }

        val applySpec = objects.newInstance(ApplyClassTweakerStepSpec::class.java)
        applySpec.classTweakerFiles.from(
            environment.accessWideners, environment.accessTransformers,
            environment.generatedAccessFiles, localIis
        )
        // user-authored headerless ATs (`accesstransformer.cfg`,
        // `*_at.cfg` in resources/`accessFile`) are dev-named —
        // 1.x `at2aw` convention
        applySpec.headerlessNamespace.set(devNamespace)
        // `access { validate = true }` — own declarations only; the
        // dep channel (dependencyClassTweakerFiles) never validates
        applySpec.validate.set(project.provider {
            environment.effectiveAccessSpecs()
                .any { it.validate }
        })
        // dep-declared classTweakers use the loader family's generic
        // `intermediary` header; the tree's prod column may be named
        // otherwise (Ornithe's is `calamus`) — alias rather than fail.
        applySpec.namespaceAliases.putAll(project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family == "fabric") {
                mapOf("intermediary" to descriptor.effectiveProdNamespace(environment))
            } else emptyMap()
        })
        // coordinate-consumed access docs
        // (`unimined<Env>AccessTransformer("g:a:v")` deps) — dep-
        // authored, no transitive filter (the coordinate declaration
        // is explicit intent, like dep-jar transitive gating absent)
        applySpec.dependencyClassTweakerFiles.from(environment.consumedAccessDocs)
        applySpec.dependencyClassTweakerFiles.from(project.provider {
            // a dependency tweaker without transitive-* rules
            // contributes nothing to dependents — skip it
            (extractedCts.get() + extractedIis.get()).filter {
                runCatching {
                    ClassTweaker.parse(it.fileName.toString(), it.toFile().readText())
                        .hasTransitiveRules
                }.getOrDefault(false)
            }
        })

        // the remap-analysis overlay (plan §3 role 1): the project's own
        // tweakers in full plus every dep-declared file's complete rule
        // set — analysis merges all rules from the remap batch, not just
        // transitive ones; engines rebase sections to the source
        // namespace through the mapping tree
        environmentSpec.contributeAccessModel(project.provider {
            AccessModel(
                (environment.accessWideners.files +
                        environment.generatedAccessFiles.files)
                    .filter { it.isFile }
                    .map {
                        ClassTweaker.parse(it.name, it.readText()).toAccessModelSection(it.name)
                    } +
                        (extractedCts.get() + extractedIis.get() +
                                localIis.get()).map {
                            ClassTweaker.parse(it.fileName.toString(), it.toFile().readText())
                                .toAccessModelSection(it.fileName.toString())
                        }
            )
        })

        environmentSpec.contributeStep(RemapSurface.JAR, applySpec)
        val accessSpec = AccessRemapStepSpec()
        environmentSpec.contributeStep(RemapSurface.JAR, accessSpec)
        // loaderSpec/devNamespace are unpopulated until the build
        // script's loader(...) runs — the plain-class fields fill in
        // after DSL eval; the contributed instance serializes later,
        // at transform registration. `namespaceLabels` relabels output
        // headers to the canonical names the loader's
        // MappingConfiguration accepts (mirroring the mappings.tiny
        // export's nsRenamer).
        project.afterDslEval {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
                ?.takeIf { it.family == "fabric" }
            if (descriptor != null) {
                accessSpec.namespaceAliases =
                    mapOf("intermediary" to descriptor.prodNamespace)
                // `named` input headers alias to the dev column —
                // unimined emits `named` in dev jars, loom-dev artifacts
                // do the same; raw dev-ns names match the tree directly
                accessSpec.devNamespaceAliases = setOf("named")
                if (environment.obfuscated.get()) {
                    // the declared dev-namespace override is a cheap
                    // DSL-property read; the derived name is NOT — it
                    // parses the mapping tree, so it defers to step
                    // execution via devNamespaceLabel
                    val declaredDevNs = environment.devNamespace.orNull
                    accessSpec.namespaceLabels = buildMap {
                        put(descriptor.prodNamespace, "intermediary")
                        declaredDevNs?.let { put(it, "named") }
                    }
                    if (declaredDevNs == null) {
                        accessSpec.devNamespaceLabel = "named"
                    }
                }
            }
        }
    }

    /**
     * Write a `classTweaker` file equivalent to a dep jar's fmj
     * `custom.*injected_interfaces` declarations — one
     * `transitive-inject-interface` row per (target, interface). The
     * header declares [prodNamespace] since published fmj keys are
     * production-namespace names; `apply-class-tweaker` rebases them
     * onto the dev artifact like any other dep CT. Returns the written
     * path, or empty when the jar declares no injections.
     */
    /**
     * Local-resource counterpart of [synthesizeInjectedInterfacesCt] —
     * reads `*injected_interfaces` from the project's own mod json in
     * [file] and synthesizes a dev-namespaced `classTweaker` doc of
     * `inject-interface` rows (own channel applies all rules; the
     * `transitive-` form is for jars crossing into dependents).
     */
    private fun synthesizeLocalInjectedInterfacesCt(
        file: File, dir: Path, devNamespace: String
    ): List<Path> {
        val injections = FabricModAnalysis.injectedInterfacesFile(file.toPath())
        if (injections.isEmpty()) return emptyList()
        val body = buildString {
            append("classTweaker\tv1\t$devNamespace\n")
            for ((t, ifaces) in injections.toSortedMap()) {
                for (iface in ifaces.sorted()) {
                    append("inject-interface\t$t\t$iface\n")
                }
            }
        }
        val target = dir.resolve(
            "local-${file.nameWithoutExtension}-injected-interfaces.ct")
        // the mod json is edited in place — rewrite on change, and only
        // then (an unchanged rewrite would churn tracked inputs)
        if (!Files.isRegularFile(target) || Files.readString(target) != body) {
            Files.createDirectories(dir)
            target.writeAtomically { tmp -> Files.writeString(tmp, body) }
        }
        return listOf(target)
    }

    private fun synthesizeInjectedInterfacesCt(
        jar: File, dir: Path, prodNamespace: String
    ): List<Path> {
        val injections = FabricModAnalysis.injectedInterfaces(jar.toPath())
        if (injections.isEmpty()) return emptyList()
        val target = dir.resolve("${jar.nameWithoutExtension}-injected-interfaces.ct")
        target.produceCached { tmp ->
            val sb = StringBuilder("classTweaker\tv1\t$prodNamespace\n")
            for ((t, ifaces) in injections.toSortedMap()) {
                for (iface in ifaces.sorted()) {
                    sb.append("transitive-inject-interface\t$t\t$iface\n")
                }
            }
            Files.write(tmp, sb.toString().toByteArray())
        }
        return listOf(target)
    }

    /**
     * Register fabric's jar processors and the fmj-aware spec context:
     * `ModJavadocJarProcessor` merges mod-declared javadoc layers into
     * the environment's composed mappings. The spec context gives
     * processors `custom`-key access to `fabric.mod.json` metadata.
     * Only contributes when the loader is fabric — the processor list
     * is config-time, so the check rides the spec-context factory.
     */
    private fun contributeJarProcessors(project: Project, environment: MinecraftEnvironment) {
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(environment.environmentId)
        envSpec.jarProcessors += ModJavadocJarProcessor()
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        envSpec.jarProcessorSpecContext = { productionNamespace, modPaths ->
            fabricSpecContext(
                productionNamespace,
                if (loaderSpec.orNull?.name?.let { LoaderRegistry.resolveBuiltin(it) }?.family == "fabric") modPaths else emptyList()
            )
        }
    }

    /**
     * Fabric launches through the loader's Knot entrypoint — read the
     * resolved loader jar's `mainClass` per side so run configs get the
     * right `KnotClient`/`KnotServer` (or a loader-declared override).
     */
    private fun contributeRunMutator(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val transform = project.extensions.getByType(TransformExtension::class.java)

        val loader = project.configurations.getByName(
            "fabricLoader${environment.environmentId.capitalized()}"
        )
        val loaderFiles = project.files(loader)

        transform.sourceSet(environment.environmentId).runMutators += Action<RunConfigSpec> { config ->
            val descriptor = loaderSpec.orNull?.name?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family != "fabric") return@Action
            // `customGameProvider` — suppress the loader's builtin MC
            // game-provider lookup (1.x patcher flag; total-conversion
            // setups, typically paired with an `empty-*` version)
            if (environment.customGameProvider.get()) {
                descriptor.capabilities["skipMcProvider"]?.let {
                    config.jvmArgs += it
                }
            }
            val jar = loaderFiles.singleFile.toPath()
            val side = if (config.name.contains("server", ignoreCase = true)) "server" else "client"
            // loaders that ship fabric-loader unmodified (fabric,
            // legacyfabric, babric, ornithe) hit the fabric knot
            // path; quilt-loader carries its own knot package plus
            // compat shims that don't cover `impl.launch` — probe the
            // resolved jar rather than hardcoding either
            val knot = "Knot${side.replaceFirstChar(Char::uppercase)}"
            val fallback = sequenceOf(
                "net.fabricmc.loader.impl.launch.knot.$knot",
                "org.quiltmc.loader.impl.launch.knot.$knot"
            ).firstOrNull { main ->
                java.util.jar.JarFile(jar.toFile()).use {
                    it.getJarEntry(main.replace('.', '/') + ".class") != null
                }
            } ?: "net.fabricmc.loader.impl.launch.knot.$knot"
            config.mainClass = FabricModAnalysis.loaderMainClass(jar, side) ?: fallback
            // dev launches run the game jar in the dev (e.g. named) namespace;
            // this flag makes the loader widen non-public MC access flags at
            // class-load (PackageAccessFixer) — required since remap-induced
            // cross-package access violations are inherent to dev jars
            config.jvmArgs += "-Dfabric.development=true"
            // quilt-loader validates its target namespace against the
            // mappings jars on the classpath — fabric-loader silently skips
            // the same check. Those mappings carry the canonical `named`
            // dev column and `intermediary` prod column, and the supplied
            // game/mod jars are already remapped to that runtime namespace.
            // `official` is therefore not a valid escape hatch: it makes
            // Quilt reject named access wideners and treat remapped classes
            // as if they were still obfuscated.
            val isQuilt = java.util.jar.JarFile(jar.toFile()).use {
                it.getJarEntry("org/quiltmc/loader/impl/launch/knot/KnotClient.class") != null
            }
            if (isQuilt) {
                // quilt-loader reads its own dev-mode key —
                // `fabric.development` is dead on quilt (verified:
                // SystemProperties.DEVELOPMENT = "loader.development"
                // gates the same PackageAccessFixer install + dev
                // discovery paths)
                config.jvmArgs += "-Dloader.development=true"
                // qmj5: hand-written `quilt.mod.json` may be JSON5 in
                // dev (loom sets the same flag)
                config.jvmArgs += "-Dloader.enable_quilt_mod_json5_in_dev_env=true"
                val targetNamespace = if (config.prod) "intermediary" else "named"
                config.jvmArgs += "-Dloader.experimental.minecraft.targetNamespace=$targetNamespace"
            }
            // canonical runtime contract (see exportLoaderMappings):
            // the supplied game jar + mod jars carry internal column
            // names, so the loader must be told which canonical label
            // they map to — `named` in dev, `intermediary` in prod.
            // Mod jars arrive pre-remapped to the runtime namespace, so
            // their distribution label is the runtime one.
            if (environment.obfuscated.get()) {
                if (config.name.contains("prod", ignoreCase = true)) {
                    config.jvmArgs += "-Dfabric.gameMappingNamespace=intermediary"
                } else {
                    config.jvmArgs += "-Dfabric.gameMappingNamespace=named"
                    config.jvmArgs += "-Dfabric.defaultModDistributionNamespace=named"
                }
            }
            // the loader otherwise sniffs the version from jar bytecode
            // — renamed dev jars defeat the analyzer and version
            // predicates (conditional mixins, mod depends) then evaluate
            // against an empty version. The raw env version is fine:
            // McVersionLookup normalizes it internally.
            config.jvmArgs += "-Dfabric.gameVersion=${environment.version.get()}"
        }
    }

}
