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
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.fabric.processor.ModJavadocJarProcessor
import xyz.wagyourtail.unimined.transform.fabric.processor.fabricSpecContext
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
        // fabric's MC artifact is the vanilla jar — the transform layers
        // loader behavior on top; convention lets users override, and the
        // convention itself only applies when the loader is fabric
        environment.provider.convention(
            environment.loaderSpec.map {
                if (LoaderRegistry.resolveBuiltin(it.name)?.family == "fabric") "native" else null
            }
        )
        // loaderSpec is only set once the build script's loader(...)
        // runs — the substitution decision must happen after the DSL
        // has evaluated but before spec() snapshots librarySubstitutions
        project.afterDslEval {
            FabricDependencyContributions.contributeLibrarySubstitutions(it, environment)
        }
        FabricDependencyContributions.contributeIntermediaryMappings(project, environment)
        FabricDependencyContributions.contributeLoaderDependency(project, environment)
        contributeAccessSteps(project, environment)
        contributeRunMutator(project, environment)
        contributeJarProcessors(project, environment)
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

        // compile-visible mod buckets only (Loom's MOD_COMPILE_ENTRIES
        // where compileClasspath()): runtime-only mods' AWs are applied by
        // the loader itself at runtime, not baked into the compile artifact
        val modJars = project.configurations.create(
            "fabricModAnalysis${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(
                project.configurations.getByName(environment.modImplementationConfigurationName),
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
        // how published mods declare interface injection).
        val extractedCts = project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family != "fabric" ||
                !environment.transitiveAccessWideners.get()
            ) {
                emptyList()
            } else {
                modJarFiles.files.flatMap { jar ->
                    val jarPath = jar.toPath()
                    val dir = ctCacheDir.resolve(
                        UniminedCache.artifactKey(jarPath)
                    )
                    FabricModAnalysis.extractClassTweakers(jarPath, dir) +
                            synthesizeInjectedInterfacesCt(
                                jar, dir, descriptor.prodNamespace
                            )
                }
            }
        }

        val applySpec = objects.newInstance(ApplyClassTweakerStepSpec::class.java)
        applySpec.classTweakerFiles.from(environment.accessWideners)
        // dep-declared classTweakers use the loader family's generic
        // `intermediary` header; the tree's prod column may be named
        // otherwise (Ornithe's is `calamus`) — alias rather than fail.
        applySpec.namespaceAliases.putAll(project.provider {
            val descriptor = loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
            if (descriptor?.family == "fabric") {
                mapOf("intermediary" to descriptor.prodNamespace)
            } else emptyMap()
        })
        applySpec.dependencyClassTweakerFiles.from(project.provider {
            // a dependency tweaker without transitive-* rules
            // contributes nothing to dependents — skip it
            extractedCts.get().filter {
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
                environment.accessWideners.files
                    .filter { it.isFile }
                    .map {
                        ClassTweaker.parse(it.name, it.readText()).toAccessModelSection(it.name)
                    } +
                        extractedCts.get().map {
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
    private fun synthesizeInjectedInterfacesCt(
        jar: File, dir: Path, prodNamespace: String
    ): List<Path> {
        val injections = FabricModAnalysis.injectedInterfaces(jar.toPath())
        if (injections.isEmpty()) return emptyList()
        val target = dir.resolve("${jar.nameWithoutExtension}-injected-interfaces.ct")
        if (Files.notExists(target)) {
            Files.createDirectories(target.parent)
            val sb = StringBuilder("classTweaker\tv1\t$prodNamespace\n")
            for ((t, ifaces) in injections.toSortedMap()) {
                for (iface in ifaces.sorted()) {
                    sb.append("transitive-inject-interface\t$t\t$iface\n")
                }
            }
            Files.write(target, sb.toString().toByteArray())
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
            if (loaderSpec.orNull?.name?.let { LoaderRegistry.resolveBuiltin(it) }?.family != "fabric") return@Action
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
            // quilt-loader's MappingConfigurationImpl throws when its target
            // namespace (`intermediary`) isn't resolvable from mappings jars
            // on the classpath — fabric-loader silently skips the same check.
            // The dev run ships the pre-remapped dev jar, so 'official' is
            // quilt's "trust the supplied jar" escape hatch.
            val isQuilt = java.util.jar.JarFile(jar.toFile()).use {
                it.getJarEntry("org/quiltmc/loader/impl/launch/knot/KnotClient.class") != null
            }
            if (isQuilt) {
                config.jvmArgs += "-Dloader.experimental.minecraft.targetNamespace=official"
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
