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
import xyz.wagyourtail.unimined.transform.forge.internal.installer.bootstrapDevNeedsSemicolon
import xyz.wagyourtail.unimined.transform.forge.internal.installer.bootstrapDevVersion
import xyz.wagyourtail.unimined.transform.forge.internal.installer.detectTweakClass
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevRunConfigs

/**
 * Run-config mutators. `contributeRunMutators` translates the userdev
 * profile's `runs` block (main class, args, jvmArgs, env) into unimined
 * run mutations per environment+side; `forgeGroups` assembles the
 * shared/intermediary run-group directories.
 */
internal fun contributeRunMutators(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val transform = project.extensions.getByType(
        xyz.wagyourtail.unimined.transform.TransformExtension::class.java
    )
    val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
    val envSpec = transform.sourceSet(environment.environmentId)
    val artifacts = minecraft.artifacts(environment.environmentId)
    val cacheDir = project.layout.projectDirectory
        .dir(".gradle/unimined/forge").asFile.toPath()

    // captured at config time — the mutator runs at execution,
    // where Project is unavailable under configuration cache
    val groups = forgeGroups(project, envSpec, minecraft)
    val mcLibs = project.configurations.getByName(
        environment.minecraftLibrariesConfigurationName
    )
    val devView = project.configurations.findByName(
        environment.minecraftDevViewName
    )
    val modSourceView = project.configurations.findByName(
        environment.minecraftModSourceViewName
    )
    // `forge:srgRuntime` — opt-in srg-namespace launch (mod-source
    // view carries the searge artifact for FG2-era envs); read
    // lazily so post-wiring step declarations still count
    val srgRuntime = project.provider {
        envSpec.pipeline.declaredSteps.any { it.id == "forge:srgRuntime" }
    }
    // `{modules}` — userdev `modules` coords resolved as detached
    // non-transitive configs (1.x matched them against the mc
    // libraries list; same resolution, less bookkeeping)
    val moduleFiles = project.provider {
        val profile = resolved.orNull?.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        profile.config.modules.map { coord ->
            project.configurations.detachedConfiguration(
                project.dependencies.create(coord.removeSuffix("@jar"))
            )
                .apply { isTransitive = false }.files.single().absolutePath
        }
    }
    // `{minecraft_classpath_file}` — 1.x `legacy_classpath.txt`:
    // mc libraries + the dev artifact + client-extra (already on
    // the libs view), newline-joined
    val classpathFile = project.provider {
        val file = cacheDir.resolve("classpath-${environment.environmentId}.txt")
        java.nio.file.Files.createDirectories(cacheDir)
        val jarView = if (srgRuntime.get()) modSourceView else devView
        val files = mcLibs.files + (jarView?.files ?: emptySet())
        java.nio.file.Files.write(
            file,
            files.joinToString("\n") { it.absolutePath }.toByteArray()
        )
        file.toString()
    }
    // unprotect — always-on for Userdev (1.x FG3 parity:
    // `unprotectRuntime = true`). The agent needs tiny mappings
    // with dst ns literally `named` to identify MC classes
    val unprotectJar = project.provider {
        project.configurations.detachedConfiguration(
            project.dependencies.create("io.github.juuxel:unprotect:1.3.0")
        )
            .apply { isTransitive = false }.files.single()
    }
    val unprotectMappings = project.provider {
        exportUnprotectMappings(artifacts.get().mappings.get(), cacheDir)
    }
    val devNs = minecraft.devNamespace(environment.environmentId)
    envSpec.runMutators += org.gradle.api.Action<xyz.wagyourtail.unimined.api.spec.RunConfigSpec> { run ->
        val r = resolved.orNull ?: return@Action
        val ctx = UserdevRunConfigs.Ctx(
            assetIndex = { artifacts.get().assets.get().id },
            groups = { groups },
            modules = { moduleFiles.get() },
            minecraftClasspathFile = { classpathFile.get() },
            srgToMcpAsSrg = {
                exportSrgToMcp(
                    artifacts.get().mappings.get(),
                    r.descriptor.prodNamespace, devNs.get(), cacheDir
                )
            },
            groupSeparator = bootstrapDevVersion(r)?.let {
                if (bootstrapDevNeedsSemicolon(it)) ";" else
                    java.io.File.pathSeparator
            } ?: java.io.File.pathSeparator,
        )
        // `${x}`-form substitution suppliers — FG emits both
        // `{x}` (handled in [Ctx.argValue]) and `${x}` (resolved
        // against these at launch, 1.x `RunConfig.properties`)
        run.properties["modules"] = {
            ctx.modules().joinToString(java.io.File.pathSeparator)
        }
        run.properties["minecraft_classpath_file"] =
            { ctx.minecraftClasspathFile() }
        run.properties["source_roots"] = { ctx.argValue("{source_roots}") }
        run.properties["mcp_mappings"] = { "unimined.stub" }
        when (r.profile) {
            is ForgeProfile.Userdev -> {
                val defaultTweak = "net.minecraftforge.fml.common.launcher." +
                        if (run.name == "server") "FMLServerTweaker" else "FMLTweaker"
                val spec = UserdevRunConfigs.forSide(
                    r.profile.config,
                    run.name, defaultTweak, ctx
                ) ?: return@Action
                run.mainClass = spec.mainClass
                run.programArgs.addAll(spec.args)
                run.jvmArgs.addAll(spec.jvmArgs)
                run.environment.putAll(spec.environment)
                // 1.x FG3 `unprotectRuntime = true` — the javaagent
                // lifts sealed-package/final restrictions on MC
                // classes; mappings zip carries `named` dst ns
                run.jvmArgs.addAll(
                    listOf(
                        "-javaagent:${unprotectJar.get().absolutePath}",
                        "-Dunprotect.mappings=${unprotectMappings.get()}"
                    )
                )
            }

            is ForgeProfile.Binpatches -> {
                val profile = r.profile
                if (run.name != "client" && run.name != "server") return@Action
                // FG2 GradleStart/launchwrapper shape — the dev
                // package's dev.json carries the authoritative
                // mainClass/minecraftArguments; jar-content tweak
                // detection is the universal-jar fallback
                run.mainClass = run.mainClass ?: (profile.mainClass
                    ?: "net.minecraft.launchwrapper.Launch")
                if (profile.programArgs.isNotEmpty()) {
                    run.programArgs.addAll(profile.programArgs)
                } else {
                    detectTweakClass(r.jar, run.name)?.let {
                        run.programArgs.addAll(listOf("--tweakClass", it))
                    }
                }
                run.jvmArgs.addAll(
                    listOf(
                        "-Dfml.ignoreInvalidMinecraftCertificates=true",
                        "-Dfml.deobfuscatedEnvironment=true",
                        "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${ctx.srgToMcpAsSrg()}"
                    ) + if (srgRuntime.get()) listOf(
                        // keep FML's deobf transformer registered —
                        // DeobfTweakerTx's strip reads this flag at
                        // launch; the modSource-view jar is srg-named
                        "-Dunimined.fg2.runtimeDeobf"
                    ) else emptyList()
                )
                run.environment["MOD_CLASSES"] =
                    UserdevRunConfigs.groupsString(groups)
            }

            else -> {}
        }
    }
}

/**
 * 1.x `sortProjectSourceSets`: `MOD_CLASSES` groups as
 * `projectPath:environmentId` -> output dirs. Members of an
 * inherited environment merge into this group when that env is not
 * itself forge-transformed; each forge-family inherited env forms
 * its own group. Same-project inheritance only — cross-project
 * `EnvironmentReference`s are skipped (Gradle isolated-project
 * resolution is not available at config time).
 */
internal fun forgeGroups(
    project: Project,
    spec: xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec,
    minecraft: MinecraftExtension
): Map<String, List<String>> {
    fun dirsFor(projectPath: String, envId: String): List<String> {
        val proj = if (projectPath == project.path) project
        else project.rootProject.project(projectPath)
        val sourceSet = proj.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName(envId)
        return listOf(sourceSet.output.resourcesDir!!.absolutePath) +
                sourceSet.output.classesDirs.files.map { it.absolutePath }
    }

    val ownKey = "${project.path}:${spec.environmentId}"
    val groups = linkedMapOf(
        ownKey to dirsFor(project.path, spec.environmentId).toMutableList()
    )
    for ((refPath, refEnvId) in spec.inheritedEnvironmentReferences()) {
        // only same-project environments can be classified
        if (refPath != project.path) continue
        val inheritedIsForge = LoaderRegistry
            .resolveBuiltin(
                minecraft.environments.findByName(refEnvId)
                    ?.loaderSpec?.orNull?.name ?: ""
            )
            ?.family == "forge"
        val dirs = dirsFor(refPath, refEnvId)
        if (inheritedIsForge) {
            groups.getOrPut(
                "$refPath:$refEnvId"
            ) { mutableListOf() }
                .addAll(dirs)
        } else {
            groups.getValue(ownKey).addAll(dirs)
        }
    }
    return groups
}

/**
 * FG2 `tweakClass` detection by jar content — the FML tweaker
 * lives under `cpw.mods` on mc <=1.7.10 and `net.minecraftforge`
 * on 1.8-1.12.2 (1.x `applyClientRunTransform`/`...Server`).
 */
