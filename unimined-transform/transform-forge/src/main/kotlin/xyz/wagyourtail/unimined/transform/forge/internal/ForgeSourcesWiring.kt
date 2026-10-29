package xyz.wagyourtail.unimined.transform.forge.internal

import xyz.wagyourtail.commonskt.utils.capitalized
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
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeSourcesTask
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge
import xyz.wagyourtail.unimined.transform.forge.internal.installer.bootstrapDevVersion
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.GradleExecPort
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.userdevAts

/**
 * Dev-source and library wiring. `contributeDevSources` registers the
 * `forgeSources` task + the installer/atTool detached configurations;
 * `mcpconfigCleanJar` drives [McpConfigRunner] for the MCPConfig-era
 * rename/merge pipeline; `contributeUserdevLibraries` attaches the
 * userdev-declared library set onto the environment classpath.
 */
internal fun contributeDevSources(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val environmentId = environment.environmentId
    val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
    val userdevProfile = resolved.map { it.profile as? ForgeProfile.Userdev }

    val taskName = if (environmentId == "main") "forgeSources"
    else "forgeSources${environmentId.capitalized()}"
    val task = project.tasks.register(taskName, ForgeSourcesTask::class.java) { t ->
        t.group = "unimined"
        t.description =
            "Runs the userdev mcpconfig chain for '$environmentId' sources"
        t.userdevJar.set(project.layout.file(resolved.map { it.jar.toFile() }))
        t.mcpConfigZip.set(project.layout.file(project.provider {
            val profile = userdevProfile.orNull ?: return@provider null
            val conf = project.configurations.detachedConfiguration(
                project.dependencies.create(profile.config.mcp)
            )
            conf.isTransitive = false
            conf.files.single()
        }))
        val artifacts = minecraft.artifacts(environmentId)
        // raw side jars come through aux — the side-keyed
        // `artifacts` map only carries the environment's side
        t.clientJar.set(artifacts.flatMap {
            it.auxiliaryArtifacts["client_jar"]!!
        })
        t.serverJar.set(project.layout.file(project.provider {
            artifacts.get().auxiliaryArtifacts["server_jar"]?.get()?.asFile
        }))
        t.clientMappings.set(project.layout.file(project.provider {
            artifacts.get().auxiliaryArtifacts["client_mappings"]?.get()?.asFile
        }))
        t.serverMappings.set(project.layout.file(project.provider {
            artifacts.get().auxiliaryArtifacts["server_mappings"]?.get()?.asFile
        }))
        t.libraries.from(
            project.configurations.getByName(
                environment.minecraftLibrariesConfigurationName
            )
        )
        t.useToolchains.set(environment.useToolchains)
        t.mcJavaVersion.set(environment.requiredJavaVersion)
        t.sourcesJar.set(project.layout.file(project.provider {
            val r = resolved.orNull ?: return@provider null
            val base = r.descriptor.loaderCoords(
                environment.version.get(),
                environment.loaderSpec.get().version
            )
            runCatching {
                project.configurations.detachedConfiguration(
                    project.dependencies.create("$base:sources")
                )
                    .apply { isTransitive = false }.files.single()
            }.getOrNull()
        }))
        t.atFiles.from(environment.accessTransformers)
        t.atToolJars.from(project.provider {
            val r = resolved.orNull ?: return@provider emptyList<Any>()
            val version = r.descriptor.capabilities["atTool"] ?: "8.2.2"
            val conf = project.configurations.detachedConfiguration(
                project.dependencies.create(
                    "net.minecraftforge:accesstransformers:$version"
                )
            )
            conf.isTransitive = true
            conf.files.toList()
        })
        t.toolIndex.putAll(project.provider {
            val profile = userdevProfile.orNull ?: return@provider emptyMap()
            (profile.config.json["functions"] as? JsonObject)
                ?.keys?.mapNotNull { key ->
                    val fn = (profile.config.json["functions"]
                            as? JsonObject)?.get(key)?.jsonObject
                    val version = fn?.get("version")
                        ?.jsonPrimitive?.contentOrNull
                    version
                }?.associateWith { coord ->
                    project.configurations.detachedConfiguration(
                        project.dependencies.create(coord)
                    )
                        .apply { isTransitive = true }
                        .files.map { it.absolutePath }
                } ?: emptyMap()
        })
        t.side.set(environment.side.map {
            when (it) {
                Side.CLIENT -> "client"
                Side.SERVER -> "server"
                Side.MERGED -> "joined"
            }
        })
        t.userdevConfigJson.set(userdevProfile.map {
            it.config.json.toString()
        })
        t.outputJar.set(
            project.layout.buildDirectory.file(
                "unimined/sources/minecraft-$environmentId-userdev.jar"
            )
        )
    }

    // the genSources override — absent until a Userdev profile or
    // an FG2 dev package is probed, so other environments keep
    // the decompiler path
    environment.devSources.set(project.layout.file(project.provider {
        val r = resolved.orNull ?: return@provider null
        when (val profile = r.profile) {
            is ForgeProfile.Userdev ->
                task.get().outputJar.get().asFile
            // FG2 dev package: `sources.zip` is the srg-named
            // pre-decompiled source set — extract and hand to the
            // SOURCES surface for searge -> devNs remap
            is ForgeProfile.Binpatches ->
                profile.devPackage?.let {
                    UserdevExtract.extractEntries(
                        it.jar,
                        listOf("sources.zip"),
                        UniminedCache.global(project).resolve(
                            "forge/fg2-sources/" +
                                    UniminedCache.artifactKey(it.jar)
                        )
                    )
                        .firstOrNull()?.toFile()
                }

            else -> null
        }
    }))
    environment.devSourcesNamespace.set(resolved.map {
        when (val profile = it.profile) {
            is ForgeProfile.Userdev -> profile.config.obfNamespace
            is ForgeProfile.Binpatches ->
                // FG2 dev content is srg-named under either
                // metadata shape (dev package / universal
                // version.json); a bare universal is obf
                if (profile.devPackage != null || profile.versionJson != null)
                    "searge" else "official"

            else -> "official"
        }
    })
}

/**
 * `Userdev` profile: `client-extra-<ver>.jar` — every non-`.class`,
 *  non-`META-INF` resource from the raw Minecraft artifact, published
 *  as a `minecraftLibraries` file dep so dev runtimes see client
 *  resources (1.x `createClientExtra`). Built lazily inside the
 *  provider body, same extraction-in-provider pattern as
 *  [userdevAts]. For joined environments the raw view is the merged
 *  artifact — its client+server resource superset is harmless on the
 *  dev classpath.
 */
internal fun mcpconfigCleanJar(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: ResolvedForge
): File? {
    val profile = resolved.profile as? ForgeProfile.Userdev
        ?: return null
    val config = profile.config
    val envId = environment.environmentId


    val mcpConf = project.configurations.detachedConfiguration(
        project.dependencies.create(config.mcp)
    )
    mcpConf.isTransitive = false
    val mcpZip = mcpConf.files.single().toPath()

    val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
    val artifacts = minecraft.artifacts(envId).get()
    fun aux(key: String) = artifacts.auxiliaryArtifacts.getValue(key)
        .get().asFile.toPath()

    val side = when (environment.side.get()) {
        Side.CLIENT -> "client"
        Side.SERVER -> "server"
        Side.MERGED -> "joined"
    }
    // step outputs are content-keyed by (mcpconfig coord + side +
    // step chain) — global cache; coord carries the MC version, so
    // unrelated envs/versions never collide
    val cacheDir = UniminedCache.global(project)
        .resolve(
            "forge/mcpconfig/" +
                    config.mcp.replace(Regex("[^A-Za-z0-9._-]+"), "-") +
                    "/$side"
        )

    val env = McpConfigRunner.Env(
        clientJar = { aux("client_jar") },
        serverJar = { aux("server_jar") },
        clientMappings = { aux("client_mappings") },
        serverMappings = { aux("server_mappings") },
        libraries = {
            project.configurations.getByName(
                environment.minecraftLibrariesConfigurationName
            )
                .files.map { it.toPath() }
        },
        toolClasspath = { coord ->
            project.configurations.detachedConfiguration(
                project.dependencies.create(coord)
            )
                .apply { isTransitive = true }
                .files.map { it.toPath() }
        },
        exec = project.objects.newInstance(GradleExecPort::class.java).apply {
            useToolchains = { environment.useToolchains.get() }
            defaultJavaVersion = { environment.requiredJavaVersion.get() }
        }
    )
    val runner = McpConfigRunner(mcpZip, cacheDir, side, env)
    val step = if (config.notchObf && side == "joined") "merge" else "rename"
    return runner.resultFor(step).output?.toFile()
}

/**
 * `Userdev` profile: the installer jar's `ats` path prefix is
 * extracted to the cache and applied as environment ATs — the
 * userdev ATs (`fml_at.cfg`/`forge_at.cfg`/`META-INF/
 * accesstransformer.cfg` beneath it) apply like any declared AT.
 * `[]` for non-Userdev profiles — their ATs come from the era
 * pipelines.
 */
internal fun contributeUserdevLibraries(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val config = project.configurations.create(
        "forgeUserdevLibraries${environment.environmentId.capitalized()}"
    ) { conf ->
        conf.isCanBeConsumed = false
        conf.isCanBeResolved = true
        conf.withDependencies { deps ->
            val r = resolved.orNull ?: return@withDependencies
            val coords = when (val profile = r.profile) {
                is ForgeProfile.Userdev ->
                    UserdevExtract.libraryCoords(profile.config)
                // FG2: dev.json (dev package) or version.json
                // (universal) `libraries` — launchwrapper,
                // scala/akka, ...
                is ForgeProfile.Binpatches -> profile.libraries

                else -> return@withDependencies
            }
            coords.forEach {
                deps.add(project.dependencies.create(it))
            }
            // forge >=49 (mc >=1.20.3): forge ships its own dev
            // relauncher — mirror `bootstrap:<ver>` with
            // `bootstrap-dev:<ver>` (arch-loom parity; the run's
            // `main` is already BootstrapDevMain, no wrap needed)
            if (r.profile is ForgeProfile.Userdev) bootstrapDevVersion(r)?.let {
                deps.add(
                    project.dependencies.create(
                        "net.minecraftforge:bootstrap-dev:$it"
                    )
                )
            }
        }
    }
    project.configurations.named(environment.minecraftLibrariesConfigurationName) { conf ->
        conf.extendsFrom(config)
    }
}

/**
 * `net.minecraftforge:bootstrap-dev` version for this userdev
 * profile — the `bootstrap:<ver>` entry in the config's
 * `libraries`, when the product is `forge`. `null` pre-49 or when
 * forge ships no bootstrap lib. Mirrors arch-loom's
 * `MIN_BOOTSTRAP_DEV_VERSION = 49` check via the library list
 * itself rather than a version matrix.
 */
