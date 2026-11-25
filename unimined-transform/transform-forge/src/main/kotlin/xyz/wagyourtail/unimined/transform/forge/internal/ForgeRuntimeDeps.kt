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
import xyz.wagyourtail.unimined.api.util.writeAtomically
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract

/**
 * Runtime-only artifacts. `contributeClientExtra` builds the
 * client-extra jar for side=client; `contributeSrgToMcp` produces the
 * srg→mcp runtime-bridge jar FG2-era launches consume
 * (GradleStart.srg.srg-mcp).
 */
internal fun contributeClientExtra(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val rawView = project.configurations.getByName(environment.minecraftRawViewName)
    val cacheDir = project.layout.projectDirectory
        .dir(".gradle/unimined/forge").asFile.toPath()

    val clientExtraJar = project.provider {
        // a provider yielding `null` inside `project.files` is an
        // absent value, not an empty collection — resolution visits
        // it and throws; yield an empty list instead
        val r = resolved.orNull ?: return@provider emptyList()
        if (r.profile !is ForgeProfile.Userdev) return@provider emptyList()
        val raw = rawView.files.firstOrNull { it.extension == "jar" }
            ?: return@provider emptyList()
        val out = cacheDir.resolve(
            "client-extra-${r.descriptor.id}-" +
                    "${environment.loaderSpec.orNull?.version}-" +
                    "${environment.version.get()}.jar"
        )
        UserdevExtract.writeClientExtra(raw.toPath(), out)
        listOf(out.toFile())
    }

    project.configurations.named(environment.minecraftLibrariesConfigurationName) { conf ->
        conf.dependencies.add(
            project.dependencies.create(project.files(clientExtraJar))
        )
    }
}

/**
 * `Userdev` profile: `srg2mcp.jar` — the MCP-zip `prodNs -> devNs`
 * export (`methods.csv`/`fields.csv`/`params.csv`) published as a
 * `minecraftLibraries` file dep. FML's naming service picks these
 * csvs up off the dev classpath so its searge-bound internals
 * resolve against the dev-namespace runtime — without it the dev
 * artifact can only run at srg (1.x `srgToMCPAsMCP`, added whenever
 * the run isn't legacydev LaunchWrapper and `obfNamespace !=
 * "mojmap"`).
 */
internal fun contributeSrgToMcp(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
    val cacheDir = project.layout.projectDirectory
        .dir(".gradle/unimined/forge").asFile.toPath()
    val artifacts = minecraft.artifacts(environment.environmentId)

    val srg2mcpJar = project.provider {
        val r = resolved.orNull ?: return@provider emptyList<File>()
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        val mainClass = profile.config.runs["client"]?.get("main")
            ?.jsonPrimitive?.contentOrNull ?: ""
        if (mainClass.startsWith("net.minecraftforge.legacydev") ||
            profile.config.obfNamespaceFor(!environment.obfuscated.get()) == "mojmap") {
            return@provider emptyList()
        }
        val tree = artifacts.get().mappings.get()
        // the csv table describes the *patched* jar's member ns —
        // searge for userdev-era (notchObf included: obf input, srg
        // output — `obfNamespace` would read "official" there),
        // mojmap for neoform; dst is the tree's last (mod-facing)
        // ns — srg-runtime envs resolve devNamespace to the jar's
        // own srg boundary
        val prod = profile.config.patchedNamespace
        val dev = tree.namespaces.last().name
        // unobf'd-era products (26.x+) resolve an identity mapping
        // tree — prod/dev namespaces coincide (or are absent) so
        // there is no searge->mcp hop to emit; MCPv6 csvs need
        // exactly 2 distinct namespaces.
        val nsNames = tree.namespaces.map { it.name }
        if (prod == dev || prod !in nsNames || dev !in nsNames) {
            return@provider emptyList()
        }
        listOf(
            File(
                exportSrgToMcpZip(
                    tree, prod, dev, cacheDir
                )
            )
        )
    }

    project.configurations.named(environment.minecraftLibrariesConfigurationName) { conf ->
        conf.dependencies.add(
            project.dependencies.create(project.files(srg2mcpJar))
        )
    }
}

/**
 * `dev-manifest-<env>.jar` — a manifest-only jar carrying the env's
 * `MixinConfigs`/`TweakClass` + `manifestAttributes` DSL attributes
 * onto the dev classpath for FG2-era (LaunchWrapper) launches. That
 * era's sponge services register mixin configs from jar manifests
 * alone (`manifestMixinJars`) and FML cascades `TweakClass`/
 * `FMLCorePlugin` off coremod-candidate manifests — exploded
 * classpath dirs are invisible to both, and `mixin.configs` only
 * reaches `Launch.blackboard` through `CommandLineOptions` (the
 * `Blackboard` service has no sysprop fallback). This mirrors what
 * the mod's prod jar manifest does in a real mods-dir scan.
 * Inert on later eras: ModLauncher services read the
 * `mixin.configs` sysprop instead.
 */
internal fun contributeDevManifestJar(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val cacheDir = project.layout.projectDirectory
        .dir(".gradle/unimined/forge").asFile.toPath()

    val markerJar = project.provider {
        val r = resolved.orNull ?: return@provider emptyList()
        if (!r.profile.isFmlRuntimeDeobfEra()) return@provider emptyList()
        val configs = environment.mixinConfigs.get()
        val attrs = environment.manifestAttributes.get()
        if (configs.isEmpty() && attrs.isEmpty()) {
            return@provider emptyList()
        }
        val out = cacheDir.resolve(
            "dev-manifest-${environment.environmentId}.jar")
        out.writeAtomically { tmp ->
            val manifest = java.util.jar.Manifest()
            manifest.mainAttributes.putValue("Manifest-Version", "1.0")
            if (configs.isNotEmpty()) {
                manifest.mainAttributes.putValue(
                    "MixinConfigs", configs.joinToString(","))
                manifest.mainAttributes.putValue(
                    "TweakClass",
                    "org.spongepowered.asm.launch.MixinTweaker")
            }
            attrs.forEach { (k, v) ->
                manifest.mainAttributes.putValue(k, v)
            }
            java.util.jar.JarOutputStream(
                Files.newOutputStream(tmp)).use { jos ->
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/"))
                jos.closeEntry()
                jos.putNextEntry(
                    java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
                manifest.write(jos)
                jos.closeEntry()
            }
        }
        listOf(out.toFile())
    }

    project.configurations.named(environment.minecraftLibrariesConfigurationName) { conf ->
        conf.dependencies.add(
            project.dependencies.create(project.files(markerJar))
        )
    }
}

/**
 * The obf-namespace mapping hop — 1.x `beforeMappingsResolve`:
 * `searge(mcpConfig.version)` (or `mojmap()` for neoform-family
 * `mcp`, `official` when `notchObf`). Non-Userdev profiles fall
 * back to the `official` identity channel — the Builtin source
 * must always materialize. `version` is the mcp artifact's
 * coordinate version (`searge` consumes it; other channels ignore
 * it).
 */
