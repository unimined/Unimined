package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.commonskt.utils.capitalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
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

/**
 * Per-environment fabric wiring. Binding callbacks can run before the
 * build script assigns `loader(...)`, so every contribution checks the
 * loader spec lazily — inside dependency materialization or provider
 * queries — and providers capture only spec properties, file
 * collections, and paths so they survive the configuration cache.
 */
internal object FabricEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
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
        project.afterEvaluate {
            contributeLibrarySubstitutions(it, environment)
        }
        contributeIntermediaryMappings(project, environment)
        contributeLoaderDependency(project, environment)
        contributeAccessSteps(project, environment)
        contributeRunMutator(project, environment)
        contributeJarProcessors(project, environment)
    }


    private const val ORNITHE_META = "https://meta.ornithemc.net"

    /**
     * Prepend `official -> intermediary` to the environment's mapping
     * chain when a fabric loader is declared — user mappings (e.g. yarn)
     * then compose `intermediary -> named` on top, and a bare fabric
     * environment still lands on the `intermediary` dev namespace.
     * The raw `intermediary` jar is contributed as the source; the
     * mapping materializer unwraps its `mappings/mappings.tiny`.
     */
    /** LWJGL2-era loaders need the patched LWJGL2 builds to run on
     *  modern JVMs (1.x `replaceLwjglVersion`): `legacyfabric`/`babric`/
     *  `ornithe` swap `org.lwjgl:*` onto `2.9.4+legacyfabric.8` (the
     *  1.x LegacyFabric default); BTA-era `flint` follows the 1.x BTA
     *  rule — `org.lwjgl.lwjgl:*` onto `2.9.4+legacyfabric.10`. Modern
     *  `fabric`/`quilt` run LWJGL3 and get no swap. */
    private fun contributeLibrarySubstitutions(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        when (environment.loaderSpec.orNull?.name) {
            "legacyfabric", "babric", "ornithe" ->
                environment.librarySubstitutions += "org.lwjgl" to "2.9.4+legacyfabric.8"
            "flint" ->
                environment.librarySubstitutions += "org.lwjgl.lwjgl" to "2.9.4+legacyfabric.10"
        }
        if (environment.loaderSpec.orNull?.name == "ornithe") {
            contributeOrnitheLibraryOverlay(project, environment)
        }
    }

    /** Ornithe backports slf4j/log4j + modernized deps onto pre-1.7
     *  versions via a per-generation overlay — fetch meta's versions
     *  index for the stable intermediary generation, then register the
     *  `gen{n}/libraries` overlay URL with the environment (1.x never
     *  hard-coded dep lists; the plan keeps the same shape). */
    private fun contributeOrnitheLibraryOverlay(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        val gen = try {
            val index = Json.parseToJsonElement(
                java.net.URI("$ORNITHE_META/v3/versions").toURL()
                    .openStream().use { it.readBytes().decodeToString() }
            ).jsonObject
            index["intermediaryGenerations"]?.jsonObject
                ?.get("stableIntermediaryGeneration")?.jsonPrimitive?.int ?: 2
        } catch (t: Throwable) {
            project.logger.warn(
                "[Unimined/Fabric] ornithe meta unreachable, " +
                    "defaulting library overlay to gen2: ${t.message}"
            )
            2
        }
        environment.libraryOverlays += "$ORNITHE_META/v3/versions/gen$gen/libraries"
    }

    private fun contributeIntermediaryMappings(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val version = environment.version
        val dependencies = project.dependencies

        // bound fires before the environment's DSL populates
        // loaderSpec, so the channel check runs lazily inside
        // effectiveSources — descriptor rows with
        // upstreamMappingChannel route through the env's own
        // mapping DSL, captured as defaultSources
        environment.mappingsSpec.deferredDefaultSources += {
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
        val intermediary = project.configurations.create(
            "fabricIntermediary${environment.environmentId.capitalized()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.withDependencies { deps ->
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
            if (descriptor?.family != "fabric" || descriptor.upstreamMappingChannel != null) {
                // upstreamMappingChannel rows contribute Maven sources via
                // defaultSources instead — no coord jar to resolve here
                emptyList()
            } else {
                listOf(intermediaryJar.singleFile)
            }
        })
        environment.mappingsSpec.defaultSources += MappingsSpecImpl.Source.File(files)
    }

    /** The loader jar is an intermediary-named mod dependency — it rides
     *  the environment's remapped mod view onto the dev classpath. */
    private fun contributeLoaderDependency(project: Project, environment: MinecraftEnvironment) {
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
                FabricModAnalysis.loaderLibraries(jar.toPath()).forEach {
                    deps.add(dependencies.create(it))
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
                conf.extendsFrom(loader, loaderLibraries)
            }
        }
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
                // loom parity: loader + its embedded libraries ride the
                // same view onto the mod classpath
                project.configurations.getByName(
                    "fabricLoader${environment.environmentId.capitalized()}"
                ),
                project.configurations.getByName(
                    "fabricLoaderLibraries${environment.environmentId.capitalized()}"
                )
            )
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
        }
        val modJarFiles = project.files(modJars)
        // per-mod-jar keyed extraction — global cache
        val ctCacheDir = UniminedCache.global(project).resolve("fabric/ct")
        // every dep-declared classTweaker file, unfiltered — the
        // compile-artifact input filters to transitive rules below, but
        // the analysis model needs the complete rule set
        val extractedCts = project.provider {
            if (loaderSpec.orNull?.name?.let { LoaderRegistry.resolveBuiltin(it) }?.family != "fabric" ||
                !environment.transitiveAccessWideners.get()
            ) {
                emptyList()
            } else {
                modJarFiles.files.flatMap {
                    FabricModAnalysis.extractClassTweakers(
                        it.toPath(),
                        ctCacheDir.resolve(
                            UniminedCache.artifactKey(it.toPath())
                        )
                    )
                }
            }
        }

        val applySpec = objects.newInstance(ApplyClassTweakerStepSpec::class.java)
        applySpec.classTweakerFiles.from(environment.accessWideners)
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
        environmentSpec.contributeStep(RemapSurface.JAR, AccessRemapStepSpec())
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
            val fallback = if (side == "server") {
                "net.fabricmc.loader.impl.launch.knot.KnotServer"
            } else {
                "net.fabricmc.loader.impl.launch.knot.KnotClient"
            }
            config.mainClass = FabricModAnalysis.loaderMainClass(jar, side) ?: fallback
            // dev launches run the game jar in the dev (e.g. named) namespace;
            // this flag makes the loader widen non-public MC access flags at
            // class-load (PackageAccessFixer) — required since remap-induced
            // cross-package access violations are inherent to dev jars
            config.jvmArgs += "-Dfabric.development=true"
        }
    }

}
