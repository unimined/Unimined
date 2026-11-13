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
    // where Project is unavailable under configuration cache.
    // Recomputed at projectsEvaluated so cross-project inherited
    // envs (whose sourceSets/loaderSpecs don't exist until their own
    // project configures) still fold into MOD_CLASSES groups —
    // 1.x's lazy `groups` ran post-evaluation
    val groupsRef = java.util.concurrent.atomic.AtomicReference(
        forgeGroups(project, envSpec, minecraft))
    project.gradle.projectsEvaluated {
        groupsRef.set(forgeGroups(project, envSpec, minecraft))
    }
    val mcLibs = project.configurations.getByName(
        environment.minecraftLibrariesConfigurationName
    )
    val devView = project.configurations.findByName(
        environment.minecraftDevViewName
    )
    val prodView = project.configurations.findByName(
        environment.minecraftProdViewName
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
    // mc libraries + the run's MC artifact + client-extra (already
    // on the libs view), newline-joined. Per-run-name file: dev,
    // srg-runtime and prod launches carry different MC artifacts and
    // a shared path would race.
    fun classpathFileFor(run: xyz.wagyourtail.unimined.api.spec.RunConfigSpec): String {
        val file = cacheDir.resolve(
            "classpath-${environment.environmentId}-${run.name}.txt")
        java.nio.file.Files.createDirectories(cacheDir)
        val jarView = when {
            run.prod -> project.configurations.findByName(
                environment.prodRunViewName)
            srgRuntime.get() -> prodView
            else -> devView
        }
        val files = mcLibs.files + (jarView?.files ?: emptySet())
        java.nio.file.Files.write(
            file,
            files.joinToString("\n") { it.absolutePath }.toByteArray()
        )
        return file.toString()
    }
    // `-DlegacyClassPath` — pathSeparator-joined view of the same file.
    // `CommonDevLaunchHandler.findClassPath` reads this property to
    // locate `client-extra` and `forge-<ver>` for `getMinecraftPaths`;
    // unset it falls back to `java.class.path`, where
    // `forge-<ver>-userdev.jar` sorts before the transformed artifact —
    // the userdev jar (no MC classes) wins as the `minecraft` module and
    // `Class.forName(module, "net.minecraft.client.main.Main")` fails
    fun legacyClassPathFor(run: xyz.wagyourtail.unimined.api.spec.RunConfigSpec): String =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(classpathFileFor(run))
        ).trim().replace("\n", java.io.File.pathSeparator)
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
    envSpec.runMutators += org.gradle.api.Action<xyz.wagyourtail.unimined.api.spec.RunConfigSpec> { run ->
        val r = resolved.orNull ?: return@Action
        val ctx = UserdevRunConfigs.Ctx(
            assetIndex = { artifacts.get().assets.get().id },
            groups = { groupsRef.get() },
            modules = { moduleFiles.get() },
            minecraftClasspathFile = { classpathFileFor(run) },
            srgToMcpAsSrg = {
                val tree = artifacts.get().mappings.get()
                // the runtime deobf table describes the *patched*
                // jar's member namespace — searge for userdev-era
                // (notchObf included: obf input, srg output), mojmap
                // for neoform — never `obfNamespace` (the patch
                // source), which reads "official" for cleanroom
                val prodNs =
                    (r.profile as? ForgeProfile.Userdev)?.config
                        ?.patchedNamespace
                        ?: r.descriptor.prodNamespace
                // dst is the tree's last (mod-facing) ns, not
                // devNamespace: srg-runtime envs resolve that to
                // the jar's own srg boundary
                val runtimeDevNs = tree.namespaces.last().name
                if (r.descriptor.capabilities["mcpToSrgFormat"] == "tsrg") {
                    xyz.wagyourtail.unimined.transform.internal
                        .RuntimeMappingExports.export(
                            tree,
                            xyz.wagyourtail.unimined.transform.internal
                                .RuntimeMappingExports.Spec(
                                    format = xyz.wagyourtail.unimined
                                        .transform.internal
                                        .RuntimeMappingExports.Format
                                        .TSRG_V1,
                                    srcNs = prodNs,
                                    dstNs = runtimeDevNs,
                                    fileName = "srg2mcp.tsrg"
                                ),
                            cacheDir
                        ).toString()
                } else {
                    exportSrgToMcp(
                        tree,
                        prodNs, runtimeDevNs, cacheDir
                    )
                }
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
            { classpathFileFor(run) }
        run.properties["source_roots"] = { ctx.argValue("{source_roots}") }
        run.properties["mcp_mappings"] = { "unimined.stub" }
        when (r.profile) {
            is ForgeProfile.Userdev -> {
                val defaultTweak = "net.minecraftforge.fml.common.launcher." +
                        if (run.name == "server") "FMLServerTweaker" else "FMLTweaker"
                val spec = UserdevRunConfigs.forSide(
                    r.profile.config,
                    run.name, defaultTweak, ctx, prod = run.prod
                ) ?: return@Action
                run.mainClass = spec.mainClass
                // strip template args duplicating options unimined
                // already supplies — jopt-simple rejects a second
                // value for single-arg options
                // (MultipleArgumentsForOptionException on gameDir,
                // then version, at ArgumentHandler.setArgs)
                val owned = setOf(
                    "--gameDir", "--version", "--assetsDir",
                    "--assetIndex", "--username", "--uuid",
                    "--accessToken", "--clientId", "--xuid",
                    "--versionType"
                )
                run.programArgs.addAll(
                    spec.args.let { args ->
                        val out = mutableListOf<String>()
                        var i = 0
                        while (i < args.size) {
                            val a = args[i]
                            val opt = a.substringBefore('=')
                            if (opt in owned) {
                                i += if ('=' in a ||
                                    args.getOrNull(i + 1)
                                        ?.startsWith("--") != false
                                ) 1 else 2
                                continue
                            }
                            out.add(a); i++
                        }
                        out
                    }
                )
                run.jvmArgs.addAll(spec.jvmArgs)
                run.environment.putAll(spec.environment)
                // 1.x FG3 `unprotectRuntime = true` — the javaagent
                // lifts sealed-package/final restrictions on MC
                // classes; mappings zip carries `named` dst ns
                run.javaagents.add(unprotectJar.get().toPath())
                run.jvmArgs.addAll(
                    listOf(
                        "-Dunprotect.mappings=${unprotectMappings.get()}",
                        "-DlegacyClassPath=${legacyClassPathFor(run)}"
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
                    listOf("-Dfml.ignoreInvalidMinecraftCertificates=true") +
                    if (run.prod) listOf(
                        // authentic FG2 prod: shipped obf jar + FML's
                        // runtime obf→srg deobf — keep the
                        // transformer registered; the dev flags
                        // would assert an already-deobfuscated jar
                        "-Dunimined.fg2.runtimeDeobf"
                    ) else listOf(
                        "-Dfml.deobfuscatedEnvironment=true",
                        "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${ctx.srgToMcpAsSrg()}"
                    ) + if (srgRuntime.get()) listOf(
                        // keep FML's deobf transformer registered —
                        // DeobfTweakerTx's strip reads this flag at
                        // launch; the prod-view jar is srg-named
                        "-Dunimined.fg2.runtimeDeobf"
                    ) else emptyList()
                )
                run.environment["MOD_CLASSES"] =
                    UserdevRunConfigs.groupsString(groupsRef.get())
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
 * its own group. Cross-project `EnvironmentReference`s resolve
 * through `rootProject.project(refPath)` — but only once every
 * project has been evaluated: callers evaluate this eagerly AND
 * again inside `gradle.projectsEvaluated`, so refs whose target
 * sourceSet/loaderSpec isn't declared yet contribute nothing in
 * the early pass and land correctly in the late one.
 */
internal fun forgeGroups(
    project: Project,
    spec: xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec,
    minecraft: MinecraftExtension
): Map<String, List<String>> {
    // SecureJar unions each MOD_CLASSES group and names the union from
    // its manifest — `JarMetadata.from` reads the *last* member carrying
    // `META-INF/`, so a manifest in the trailing member always wins
    // (member order is reversed again by bootstrap's `secureJar`, making
    // the appended member last in both the scanned classpath union and
    // the module-layer union). Without one, the union root's filename is
    // empty and `SecureJar.from` crashes with `Invalid module name: ''`
    // (upstream MinecraftForge MOD_CLASSES footgun; arch-loom #215).
    fun manifestMemberFor(key: String): String {
        val moduleName = "unimined.dev." + key.replace(":", "_")
            .trim('_').ifEmpty { "env" }
        val dir = project.layout.projectDirectory
            .dir(".gradle/unimined/forge/mod-classes/" +
                    key.replace(Regex("[^A-Za-z0-9._-]+"), "_"))
            .asFile.toPath()
        val manifest = dir.resolve("META-INF/MANIFEST.MF")
        java.nio.file.Files.createDirectories(manifest.parent)
        java.nio.file.Files.writeString(
            manifest,
            "Manifest-Version: 1.0\nAutomatic-Module-Name: $moduleName\n"
        )
        return dir.toAbsolutePath().toString()
    }

    fun dirsFor(projectPath: String, envId: String): List<String> {
        val proj = if (projectPath == project.path) project
        else project.rootProject.project(projectPath)
        // findByName — a sibling project's sourceSets may not exist in
        // the early (pre-projectsEvaluated) pass
        val sourceSet = proj.extensions
            .findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            ?.findByName(envId) ?: return emptyList()
        return listOfNotNull(sourceSet.output.resourcesDir?.absolutePath) +
                sourceSet.output.classesDirs.files.map { it.absolutePath }
    }

    val ownKey = "${project.path}:${spec.environmentId}"
    val groups = linkedMapOf(
        ownKey to (dirsFor(project.path, spec.environmentId) +
                manifestMemberFor(ownKey)).toMutableList()
    )
    for ((refPath, refEnvId) in spec.inheritedEnvironmentReferences()) {
        // loader identity lives on the *referenced* project's
        // MinecraftExtension for cross-project envs; unevaluated
        // siblings classify as non-forge in the early pass and get
        // reclassified at projectsEvaluated
        val refMinecraft = (if (refPath == project.path) project
        else project.rootProject.findProject(refPath))
            ?.extensions
            ?.findByType(MinecraftExtension::class.java)
        val inheritedIsForge = LoaderRegistry
            .resolveBuiltin(
                refMinecraft?.environments?.findByName(refEnvId)
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
            // inherited non-forge env dirs merge into the owning
            // group — they must land *before* its manifest member so
            // the manifest stays the union's last META-INF carrier
            val own = groups.getValue(ownKey)
            own.addAll(own.size - 1, dirs)
        }
    }
    // a forge-family inherited env forms its own group — it needs
    // its own trailing manifest member too
    groups.entries.forEach { (key, dirs) ->
        if (key != ownKey &&
            dirs.lastOrNull()?.contains("mod-classes") != true
        ) dirs.add(manifestMemberFor(key))
    }
    return groups
}

/**
 * FG2 `tweakClass` detection by jar content — the FML tweaker
 * lives under `cpw.mods` on mc <=1.7.10 and `net.minecraftforge`
 * on 1.8-1.12.2 (1.x `applyClientRunTransform`/`...Server`).
 */
