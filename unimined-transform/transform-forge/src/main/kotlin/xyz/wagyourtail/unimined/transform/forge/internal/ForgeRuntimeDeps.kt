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
            "client-extra-${r.descriptor.id}-${environment.loaderSpec.orNull?.version}.jar"
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
    val devNs = minecraft.devNamespace(environment.environmentId)
    val artifacts = minecraft.artifacts(environment.environmentId)

    val srg2mcpJar = project.provider {
        val r = resolved.orNull ?: return@provider emptyList<File>()
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        val mainClass = profile.config.runs["client"]?.get("main")
            ?.jsonPrimitive?.contentOrNull ?: ""
        if (mainClass.startsWith("net.minecraftforge.legacydev") ||
            profile.config.obfNamespace == "mojmap") {
            return@provider emptyList()
        }
        listOf(
            File(
                exportSrgToMcpZip(
                    artifacts.get().mappings.get(),
                    r.descriptor.prodNamespace, devNs.get(), cacheDir
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
 * The obf-namespace mapping hop — 1.x `beforeMappingsResolve`:
 * `searge(mcpConfig.version)` (or `mojmap()` for neoform-family
 * `mcp`, `official` when `notchObf`). Non-Userdev profiles fall
 * back to the `official` identity channel — the Builtin source
 * must always materialize. `version` is the mcp artifact's
 * coordinate version (`searge` consumes it; other channels ignore
 * it).
 */
