package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.commonskt.utils.capitalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
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
        descriptor.capabilities["libraryOverlayMeta"]?.let { meta ->
            contributeLibraryOverlay(project, environment, meta)
        }
    }

    /** Ornithe-era loaders backport slf4j/log4j + modernized deps onto
     *  old MC via a per-generation overlay — fetch the meta's versions
     *  index for the stable intermediary generation, then register the
     *  `gen{n}/libraries` overlay URL with the environment (1.x never
     *  hard-coded dep lists; the plan keeps the same shape). Drives off
     *  the descriptor's `libraryOverlayMeta` capability, not the
     *  loader name. */
    fun contributeLibraryOverlay(
        project: Project,
        environment: MinecraftEnvironment,
        meta: String
    ) {
        val gen = try {
            val index = Json.parseToJsonElement(
                java.net.URI("$meta/v3/versions").toURL()
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
        environment.libraryOverlays += "$meta/v3/versions/gen$gen/libraries"
        // 1.x OrnitheFabricMinecraftTransformer's `Calamus-Generation`
        // manifest attr — the resolved gen, not a descriptor constant
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
                environment.mappingsSpec.customIntermediaries
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
                    environment.mappingsSpec.customIntermediaries
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
                environment.mappingsSpec.customIntermediaries ||
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
            environment.modImplementationConfigurationName,
            environment.modRuntimeOnlyConfigurationName
        ).forEach { bucket ->
            project.configurations.matching { it.name == bucket }.all { conf ->
                conf.withDependencies {
                    val spec = loaderSpec.orNull
                    val descriptor = spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }
                        ?.takeIf { it.family == "fabric" } ?: return@withDependencies
                    val platforms = mutableSetOf(
                        "net.fabricmc:fabric-loader",
                        "org.quiltmc:quilt-loader"
                    )
                    descriptor.loaderCoords(environment.version.get(), spec.version)
                        .split(":").takeIf { it.size >= 2 }
                        ?.let { platforms += "${it[0]}:${it[1]}" }
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
        sourceSet.runtimeClasspath += project.files(project.provider {
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
        })
    }
}
