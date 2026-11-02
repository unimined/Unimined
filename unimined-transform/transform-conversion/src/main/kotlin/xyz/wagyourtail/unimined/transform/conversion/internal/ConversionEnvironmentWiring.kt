package xyz.wagyourtail.unimined.transform.conversion.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.provider.conversion.ConversionProvider
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.transform.BundledLibraryStripStepSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Env wiring for conversion-family products. The dist jar is a
 * complete MC jar, so the transform is mostly *sourcing + identity*:
 * contribute the dist artifact as the env's declared source, set the
 * identity namespace path, and apply the descriptor's capability
 * library substitutions (patched lwjgl for lwjgl2-era dists).
 * Client+server merge rides the standard merge machinery — the dists
 * ship sided jars, and the merged env's combined artifact is the
 * merge of both dist sides.
 */
object ConversionEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val spec = environment.loaderSpec.orNull ?: return
        val descriptor = LoaderRegistry().resolve(spec.name)
            ?.takeIf { it.family == "conversion" } ?: return

        // lwjgl2-era dists ride the patched legacyfabric GAV
        descriptor.capabilities["replaceLwjgl"]?.let { target ->
            val match = descriptor.capabilities["replaceLwjglMatch"] ?: "org.lwjgl"
            environment.librarySubstitutions += match to target
        }
        // extra per-product `libsub.<key>` = "match=version" rules
        // (reindev's jinput/asm-all bumps)
        descriptor.capabilities.filterKeys { it.startsWith("libsub.") }
            .forEach { (_, rule) ->
                val idx = rule.lastIndexOf('=')
                if (idx > 0) {
                    environment.librarySubstitutions +=
                        rule.substring(0, idx) to rule.substring(idx + 1)
                }
            }

        // loaders consuming a mod folder (FoxLoader's `mods/`) —
        // prepareRun copies the run's mod jars there. Duck-typed via
        // `property()` — transform modules don't depend on runs.
        descriptor.capabilities["modsDir"]?.let { dir ->
            project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            ).sourceSet(environment.environmentId)
                .prepareTaskMutators += org.gradle.api.Action { task ->
                    @Suppress("UNCHECKED_CAST")
                    (task.property("stageInto") as?
                            org.gradle.api.provider.Property<String>)?.set(dir)
                }
        }

        // dist-source seam rides provider-conversion: the env's loader
        // claim resolves the dist jar as the MC artifact directly.

        contributeLoaderDependency(project, environment, spec, descriptor)
        contributeBundledLibraryStrip(project, environment)
    }

    /** Loader artifacts onto the env classpaths — conversion envs are
     *  identity-namespace (prod == official == dev), so loader jars
     *  ride raw, never through a remap view. `foxloader` expands per
     *  1.x `FoxLoaderMinecraftTransformer`: `invoker` always; pre-2.9
     *  (`!canCombine`) adds `common`+`<side>` from `sidedArtifacts`,
     *  post-2.9 the unified `loader` coord alone — and sided vs
     *  merged envs are mutually exclusive per-era. */
    private fun contributeLoaderDependency(
        project: Project,
        environment: MinecraftEnvironment,
        spec: xyz.wagyourtail.unimined.api.spec.LoaderSpec,
        descriptor: xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
    ) {
        // reads hoisted out of withDependencies — provider gets inside
        // a resolution hook re-enter resolution ("Recursive update")
        val mc = environment.version.get()
        val joined = environment.side.orNull == Side.MERGED
        val joinedCapable = atLeast2_9(spec.version)
        val loader = project.configurations.create(
            "conversionLoader${environment.environmentId.replaceFirstChar(Char::uppercase)}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                var addPrimary = true
                if (descriptor.id == "foxloader") {
                    if (joined && !joinedCapable)
                        throw GradleException(
                            "foxloader ${spec.version} predates 2.9 and " +
                            "cannot run in a merged (joined) env"
                        )
                    descriptor.capabilities["invoker"]?.let {
                        deps.add(project.dependencies.create(it))
                    }
                    if (!joinedCapable) {
                        // pre-2.9 has no `loader` artifact — the dep
                        // set is common + <side> instead
                        addPrimary = false
                        val side = environment.side.orNull!!.name.lowercase()
                        descriptor.capabilities["sidedArtifacts"]
                            ?.split(',')?.forEach { coord ->
                                deps.add(project.dependencies.create(
                                    coord.replace("<side>", side) + ":" + spec.version
                                ))
                            }
                    }
                }
                if (addPrimary) {
                    deps.add(
                        project.dependencies.create(
                            descriptor.loaderCoords(mc, spec.version)
                        )
                    )
                }
            }
        }
        val sourceSet = project.extensions
            .getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
            .sourceSets.findByName(environment.environmentId) ?: return
        listOf(
            sourceSet.compileClasspathConfigurationName,
            sourceSet.runtimeClasspathConfigurationName,
        ).forEach { name ->
            project.configurations.named(name) { conf ->
                conf.extendsFrom(loader)
            }
        }
    }

    /** FoxLoader's joined-artifact cutoff — 1.x `canCombine`: the
     *  merged `loader` artifact exists from 2.9 onward. */
    private fun atLeast2_9(version: String): Boolean {
        val parts = version.split('.', '-')
            .mapNotNull { it.toIntOrNull() }
        return (parts.getOrElse(0) { 0 } > 2) ||
               (parts.getOrElse(0) { 0 } == 2 &&
                parts.getOrElse(1) { 0 } >= 9)
    }

    /**
     * Products declaring `extraLibraries` shade copies of those deps
     * into the dist jar — the declared coordinates ride the classpath
     * and the bundled duplicates strip out of the env artifact. Runs
     * pre-pipeline as artifact prep, scoped to the env artifact (the
     * membership set is the env's declared libraries — stripping a mod
     * jar against it would delete the mod's classes).
     *
     * MERGED envs get no step: the producer strips both sides
     * *pre-merge* inside `ConversionArtifactProducer` — the pipeline
     * only ever sees the merged output, and stripping there would
     * leave a redundant fat merged.jar in the artifact cache.
     */
    private fun contributeBundledLibraryStrip(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        if (environment.side.orNull == Side.MERGED) return
        if (!ConversionProvider.declaresExtraLibraries(environment.product.get())) return
        val strip = project.objects
            .newInstance(BundledLibraryStripStepSpec::class.java)
        strip.libraryJars.from(
            project.configurations.getByName(
                environment.minecraftDeclaredLibrariesConfigurationName
            )
        )
        project.extensions.getByType(RemapExtension::class.java)
            .sourceSet(environment.environmentId)
            .contributePreStep(
                RemapSurface.JAR, strip, StepScope.ENVIRONMENT_ARTIFACT
            )
    }
}
