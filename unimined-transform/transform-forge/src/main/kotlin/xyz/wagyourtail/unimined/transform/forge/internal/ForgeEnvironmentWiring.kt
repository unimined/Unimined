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
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
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
        // forge transforms layer onto the vanilla jar like fabric's —
        // the installer-era pipelines come later
        environment.provider.convention(
            environment.loaderSpec.map {
                if (LoaderRegistry.resolveBuiltin(it.name)?.family == "forge") "native" else null
            }
        )
        val resolved = contributeLoaderResolution(project, environment)
        // FML's userdev launch handler probes the classpath for
        // `<artifact>-<version>` (FileUtils.matchFileName =
        // `contains`) — name the dev jar after the loader coords
        // (`forge-1.20.1-47.3.0-merged.jar`, 1.x's forge-named
        // MinecraftJar) so downstream `-merged`/`-<ns>` suffixes keep
        // the needle
        environment.artifactBaseName.convention(resolved.map { r ->
            val coords = r.descriptor.loaderCoords(
                environment.version.get(),
                environment.loaderSpec.get().version
            ).split(":")
            "${coords[1]}-${coords[2]}"
        })
        // mod dependencies publish at the loader's production
        // namespace (`searge` for userdev-era, `mojmap` for neoform
        // products) — remapped views read them into devNamespace
        environment.modSourceNamespaces.convention(
            resolved.map { listOf(it.descriptor.prodNamespace) }
        )
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
        contributeDevSources(project, environment, resolved)
        contributeRunMutators(project, environment, resolved)
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
