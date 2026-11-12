package xyz.wagyourtail.unimined.transform.forge.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import org.gradle.api.plugins.ExtensionAware
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DeferredStepContribution
import xyz.wagyourtail.unimined.transform.FunctionalStepSpec
import xyz.wagyourtail.unimined.transform.forge.ForgeEnvExtension
import xyz.wagyourtail.unimined.transform.jarmod.FG1DynLibWiring
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.remapper.jst.JstStepSpec
import xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec

import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.contributeBinpatchStep
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.contributeDevFixes
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.contributeMinecraftModuleName
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.installer.contributeLoaderResolution
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevSourcesChain

/**
 * Per-environment forge-family wiring — the orchestrator. Gated on the
 * resolved [LoaderDescriptor]'s `family` — never on `loaderSpec.name` —
 * so every registered forge product (forge/lexforge/neoforge/cleanroom)
 * shares this path. Binding callbacks can run before the build script
 * assigns `loader(...)`, so contributions check the spec lazily inside
 * providers.
 *
 * The `contribute*` functions live in sibling files by concern:
 * [ForgeLoaderResolution] (which forge are we on) →
 * [ForgeSourcesWiring] (dev sources/libs) → [ForgeBinpatchWiring]
 * (patch steps) → [ForgeAccessWiring] (AT/AW/CT) →
 * [ForgeMappingsWiring] (mapping hops/exports) → [ForgeRuntimeDeps]
 * (runtime artifacts) → [ForgeRunWiring] (run configs).
 */

internal object ForgeEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        // loaderless envs (e.g. `main` under flat multiloader) carry
        // no loader — contributing loader-gated sources/steps would
        // leave loaderSpec-bound providers unset at resolve time
        // no loaderSpec gate at wire time — envs bind at declaration,
        // before `loader(...)` lands; contributions self-gate on the
        // resolved/loaderSpec providers (empty on non-forge envs)
        // forge transforms layer onto the vanilla jar like fabric's —
        // the installer-era pipelines come later
        environment.provider.convention(
            environment.loaderSpec.map {
                if (LoaderRegistry.resolveBuiltin(it.name)?.family == "forge") "native" else null
            }
        )
        val resolved = contributeLoaderResolution(project, environment)
        // merged-env side markers — 1.x `actualSideMarker`: whichever
        // SideOnly annotation the loader jar itself ships is the era
        // its runtime reads; absent → no stamping (warn, like 1.x).
        // NeoForge `Userdev` is invariant (net.neoforged.api.distmarker
        // .OnlyIn) — no jar scan. Forge `Userdev` spans both eras:
        // FG2's userdev3 jar probes as Userdev too, so scan markerJar
        // (the universal pair) for a shipped FML-era marker first —
        // modern universals ship none, which falls back to FORGE_MODERN.
        // FG1-era artifacts get detected from the runtime jar with a
        // warn-on-empty, like 1.x `actualSideMarker`.
        environment.sideMarkers.addAll(resolved.map { r ->
            r?.let {
                when {
                    it.profile is ForgeProfile.Userdev -> when {
                        it.descriptor.id.contains("neo") ->
                            listOf(xyz.wagyourtail.unimined.minecraft
                                .merge.JarMerger.SideMarker.NEOFORGE)
                        it.descriptor.id == "forge" -> listOf(
                            xyz.wagyourtail.unimined.minecraft.merge
                                .JarMerger.SideMarker
                                .detectSideMarker(it.markerJar) { }
                                ?: xyz.wagyourtail.unimined.minecraft
                                    .merge.JarMerger.SideMarker
                                    .FORGE_MODERN)
                        else -> emptyList()
                    }
                    else -> xyz.wagyourtail.unimined.minecraft.merge
                        .JarMerger.SideMarker
                        .detectSideMarker(it.markerJar) { msg ->
                            project.logger.warn(msg)
                        }?.let(::listOf) ?: emptyList()
                }
            } ?: emptyList()
        })
        // forge-era probe — `select("era")` pins resolve against the
        // probed packaging profile (preset names: "forge-userdev",
        // "forge-binpatches"); declared pins override per plan
        project.extensions
            .findByType(TransformExtension::class.java)
            ?.sourceSet(environment.environmentId)
            ?.probeResolver = { key ->
            if (key == "era") resolved.get()?.profile?.let {
                when (it) {
                    is ForgeProfile.Userdev -> "forge-userdev"
                    is ForgeProfile.Binpatches -> "forge-binpatches"
                    is ForgeProfile.ZipPatches -> "forge-zipPatches"
                    else -> null
                }
            } else null
        }
        // `jarmod:merge` env binding: the probed loader jar is the
        // FG1 zip-patches merge input — bound lazily so resolution
        // stays inside provider evaluation
        project.extensions.findByType(TransformExtension::class.java)
            ?.sourceSet(environment.environmentId)
            ?.envArtifactBindings?.set("jarmod:merge") {
                val r = resolved.get()
                r.takeIf { it.profile is ForgeProfile.ZipPatches }
                    ?.mergeJars
                    .orEmpty()
            }
        // `forge:jsCoremodRemap` — default-on for FG3-era
        // (userdev) envs: forge's universal ships coremod js that
        // lands via the loader merge, and mod jars carry .js
        // coremods; the csv naming service has no class channel,
        // so resources are rewritten src->dst directly. FG1/FG2
        // coremods are Java classes handled by the class remapper.
        // Deferred so `env.forge { jsCoremodRemap = false }` opts
        // out before realization.
        project.extensions.getByType(RemapExtension::class.java)
            .sourceSet(environment.environmentId)
            .contributeDeferredSteps(
                project.provider {
                    val ext = (environment as ExtensionAware)
                        .extensions
                        .findByType(ForgeEnvExtension::class.java)
                    if (resolved.orNull?.profile !is ForgeProfile.Userdev ||
                        ext?.jsCoremodRemap == false) emptyList()
                    else listOf(
                        DeferredStepContribution(
                            RemapSurface.JAR,
                            FunctionalStepSpec(
                                "forge:jsCoremodRemap",
                                CoremodRemapTransformStep
                            ),
                            scope = StepScope.ALL_ARTIFACTS
                        )
                    )
                }
            )
        // FML's userdev launch handler probes the classpath for
        // `<artifact>-<version>` (FileUtils.matchFileName =
        // `contains`) — name the dev jar after the loader coords
        // (`forge-1.20.1-47.3.0-merged.jar`, 1.x's forge-named
        // MinecraftJar) so downstream `-merged`/`-<ns>` suffixes keep
        // the needle
        environment.artifactBaseName.convention(
            // convention() replaces, so repeat the baseline fallback —
            // on non-forge envs `resolved` is empty and the transform
            // param must never resolve unset
            resolved.map { r ->
                val coords = r.descriptor.loaderCoords(
                    environment.version.get(),
                    environment.loaderSpec.get().version
                ).split(":")
                "${coords[1]}-${coords[2]}"
            }.orElse(environment.version.map {
                "minecraft-${environment.environmentId}-$it"
            })
        )
        // mod dependencies publish at the loader's production
        // namespace (`searge` for userdev-era, `mojmap` for neoform
        // products) — remapped views read them into devNamespace
        environment.modSourceNamespaces.convention(
            resolved.map { r ->
                // unobf'd products (26.x) dev == prod == mojmap —
                // identity remap. Otherwise the era-correct obf
                // namespace: searge for userdev-era, mojmap for
                // neoform-family — read from the profile, not the
                // static descriptor (version ranges differ)
                if (!environment.obfuscated.get()) listOf("mojmap")
                else listOf(
                    (r.profile as? ForgeProfile.Userdev)?.config?.obfNamespace
                        ?: r.descriptor.prodNamespace
                )
            }
        )
        // FG1 zip-patches era: provision FML's runtime dyn-libs — the
        // same CoreFMLLibraries scan/forgeDeps/staging/checksum-patch
        // the jarmod-family path applies, shared via FG1DynLibWiring.
        // Deferred to afterDslEval (jarmod parity): `resolved` runs a
        // detached resolution that must land after the env DSL body
        project.afterDslEval {
            val r = resolved.orNull
            // cleanroom replaces vanilla 1.12.2's platform libs with
            // modern forks (lwjgl3, netty 4.2, oshi-ffm, icu4j, jna 5)
            // declared in its userdev `libraries` — the vanilla copies
            // must not reach the classpath alongside them
            // (1.x `CleanroomMinecraftTransformer.vanillaExcludesSet`)
            if (r?.descriptor?.id == "cleanroom") {
                val dropPrefixes = listOf(
                    "com.mojang:patchy:",
                    "oshi-project:oshi-core:",
                    "com.ibm.icu:icu4j-core-mojang:",
                    "net.java.jutils:",
                    "org.lwjgl.lwjgl:",
                    "io.netty:netty-all:",
                    "net.java.dev.jna:platform:",
                )
                environment.libraryFilters += { dep ->
                    val coord = "${dep.group}:${dep.name}:${dep.version}"
                    dropPrefixes.none { coord.startsWith(it) } &&
                        !(coord.startsWith("org.lwjgl") &&
                                (dep.version ?: "").startsWith("2"))
                }
            }
            if (r?.profile is ForgeProfile.ZipPatches) {
                project.extensions.getByType(TransformExtension::class.java)
                    .sourceSet(environment.environmentId)
                    ?.let { envSpec ->
                        FG1DynLibWiring.wire(
                            project, environment, envSpec,
                            project.provider {
                                r.mergeJars.map { it.toFile() }.toSet()
                            },
                            // asm-5.2 substitution — matches jarmod's
                            // default (FG1-era FML discovery can't
                            // parse Java 8 classfiles under asm-4.x);
                            // a forge-side opt-out knob can land with
                            // the ForgeEnvExtension pass
                            useAsm52 = true,
                            // the merge rides the declared pipeline
                            // (`jarmod:merge` step), so the checksum
                            // patch must run after it to see the
                            // merged CoreFMLLibraries
                            checksumPatchSink = {
                                envSpec.postPipelineJarProcessors += it
                            },
                        )
                    }
            }
        }
        contributeLoaderDependency(project, environment, resolved)
        contributeUserdevLibraries(project, environment, resolved)
        contributeClientExtra(project, environment, resolved)
        contributeSrgToMcp(project, environment, resolved)
        contributeMappingHop(project, environment, resolved)
        // binpatch leads the pre-remap steps: it substitutes the
        // patched clean jar wholesale, so byte-mutating merges
        // (inject, universal shade) must follow it, not precede
        contributeBinpatchStep(project, environment, resolved)
        contributeAccessSteps(project, environment, resolved)
        contributeDevFixes(project, environment, resolved)
        contributeMinecraftModuleName(project, environment, resolved)
        contributeDevSources(project, environment, resolved)
        contributeRunMutators(project, environment, resolved)
        // neoforge >1.20.1 (real neoforge): prod runs at mojmap — the
        // searge-era refmap is dead weight. 1.x forced
        // `disableRefmap()` on the remap task; here it lands as a
        // convention on the auto-appended ModArtifactsStepSpec — an
        // explicit `modArtifacts {}` declaration overrides it
        project.extensions.getByType(RemapExtension::class.java)
            .sourceSet(environment.environmentId)
            .modArtifactsConventions += org.gradle.api.Action { spec ->
                val r = resolved.orNull ?: return@Action
                if (r.descriptor.id == "neoforge" &&
                    environment.version.get() != "1.20.1") {
                    spec.emitRefmap = false
                }
            }
    }

    /**
     * `Userdev` profile: `genSources` consumes the mcpconfig sources
     * chain instead of decompiling. Registers `forgeSources<Env>` —
     * the runner + [UserdevSourcesChain] splice-ins producing
     * `forgePatch` — and points [MinecraftEnvironment.devSources] at
     * it. Tool/classpath resolution is expressed as lazy task inputs
     * (detached configs inside providers, matching
     * [contributeLoaderResolution]).
     */

}

    /** `forgeLoader<Env>` — the loader's API artifact on the env's
     *  compile+runtime classpath (fabric's `contributeLoaderDependency`
     *  parity). Neoform-era loaders ship the API jar separately from
     *  the patched product; userdev-era forge classes merge into the
     *  product via binpatch, but the extra jar is harmless there too —
     *  `withDependencies` gates on forge-family so non-forge envs get
     *  nothing. */
    private fun contributeLoaderDependency(
        project: Project,
        environment: MinecraftEnvironment,
        resolved: Provider<xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge>
    ) {
        val loaderSpec = environment.loaderSpec
        val loader = project.configurations.create(
            "forgeLoader${environment.environmentId.replaceFirstChar { it.uppercaseChar() }}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = true
            // loader poms may declare `net.minecraft:*` placeholders
            // (cleanroom userdev: client extra + mappings_stable zip)
            // — the env supplies both itself; resolving them would
            // 404 on every public maven. Artifact-scoped: the same poms
            // legitimately declare `net.minecraft:launchwrapper` and
            // other real libs the run classpath needs
            for (artifact in listOf("client", "server", "merged",
                "mappings_stable", "mappings_snapshot")) {
                conf.exclude(mapOf("group" to "net.minecraft", "module" to artifact))
            }
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                val descriptor = spec?.name?.let { LoaderRegistry.resolveBuiltin(it) }
                    ?.takeIf { it.family == "forge" }
                if (descriptor != null) {
                    // era-correct classifier — bare `g:a:v` jars don't
                    // exist for FG2-era Forge (universal/userdev only).
                    // FG1 zip-patches artifacts merge into the MC jar —
                    // the raw zip must not ride the classpath: its
                    // unpatched CoreFMLLibraries would shadow the
                    // checksum-patched copy in the merged jar
                    resolved.orNull?.let { r ->
                        if (r.profile !is ForgeProfile.ZipPatches)
                            deps.add(project.dependencies.create(r.notation))
                    }
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
