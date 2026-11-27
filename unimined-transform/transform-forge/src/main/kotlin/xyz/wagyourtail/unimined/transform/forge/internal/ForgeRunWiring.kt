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
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.api.util.uniminedTools
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.transform.forge.task.WriteRunClasspath
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
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec

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

    // `groups` stays execution-lazy: each project's env binding
    // contributes its own (path:envId → dirs, loaderName) entry into
    // the shared RunGroupModel at configuration, so reads here see
    // every cross-project contribution without a projectsEvaluated
    // recompute
    val groupsProvider = { forgeGroups(project, envSpec, minecraft) }
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
    // a shared path would race. The file is produced by a per-run
    // `writeRunClasspath<Env><Run>` task (below) that the run task
    // depends on — the providers here only carry its path.
    fun classpathPathFor(runName: String): Path =
        cacheDir.resolve("classpath-${environment.environmentId}-$runName.txt")

    envSpec.runTaskContributions += org.gradle.api.Action<
            TransformEnvironmentSpec.RunTaskContext> { ctx ->
        // `run<Name>` and its junit `test<Name>` twin share one run
        // name → one file → one writer; both depend on it
        val envCap = environment.environmentId.replaceFirstChar(Char::uppercase)
        val envInfix = if (environment.environmentId == "main") "" else envCap
        val writerName = "write${envInfix}RunClasspath${
            ctx.name.replaceFirstChar(Char::uppercase)}"
        val legacyWriterName = "writeRunClasspath$envCap${
            ctx.name.replaceFirstChar(Char::uppercase)}"
        val writer = project.tasks.findByName(writerName)
            ?.let { project.tasks.named(writerName, WriteRunClasspath::class.java) }
            ?: project.tasks.register(writerName, WriteRunClasspath::class.java) { t ->
                t.group = "unimined"
                t.description = "Writes the legacy classpath file for " +
                        "run '${ctx.name}' (env '${environment.environmentId}')"
                t.classpath.from(project.provider {
                    val jarView = when {
                        ctx.prod -> project.configurations.findByName(
                            environment.prodRunViewName)
                        srgRuntime.get() -> prodView
                        else -> devView
                    }
                    mcLibs.files + (jarView?.files ?: emptySet())
                })
                t.outputFile.fileValue(classpathPathFor(ctx.name).toFile())
            }
        // pre-convention `writeRunClasspath<Env><Run>` name stays as a
        // deprecated alias (the run/test twin may register it already)
        if (project.tasks.findByName(legacyWriterName) == null) {
            project.registerDeprecatedTaskAlias(legacyWriterName, writer)
        }
        ctx.task.configure { t -> t.dependsOn(writer) }
        // `-DlegacyClassPath` — pathSeparator-joined view of the same
        // file. `CommonDevLaunchHandler.findClassPath` reads this
        // property to locate `client-extra` and `forge-<ver>` for
        // `getMinecraftPaths`; unset it falls back to `java.class.path`,
        // where `forge-<ver>-userdev.jar` sorts before the transformed
        // artifact — the userdev jar (no MC classes) wins as the
        // `minecraft` module and
        // `Class.forName(module, "net.minecraft.client.main.Main")`
        // fails. Inert on launchwrapper-era (binpatches) runs — the
        // flag is simply unread there.
        //
        // The content can't go through `runMutators` spec args: those
        // snapshot into the config cache at STORE, before the writer
        // task executes — a `fileContents` provider stays lazy and
        // serializable end-to-end (the flatMap carries the writer dep).
        if ((resolved.orNull?.profile as? ForgeProfile.Userdev)
                ?.config?.noLegacyClasspath != true) {
            ctx.lazyJvmArg(
                project.providers.fileContents(
                    writer.flatMap { it.outputFile }
                ).asText.map { content ->
                    "-DlegacyClassPath=" + content.trim()
                        .replace("\n", java.io.File.pathSeparator)
                }
            )
        }
        if (ctx.prod && resolved.orNull?.profile.isFmlRuntimeDeobfEra()) {
            // FML's `ClassPatchManager` verifies the binpatch
            // pack's input checksums against the untouched Mojang
            // side jar — the merged raw artifact already carries
            // merge-side patches and fails them — so the prod
            // classpath resolves the pristine download matching
            // this run's side (the side-independent universal jar
            // rides `prodRunViewName`; fall back to MERGED for
            // products that ship no side jars). The remapped mod
            // jar itself stays on the classpath — FML's
            // ClasspathLocator discovers it there; a `mods/` copy
            // would double-register it
            ctx.task.configure { t ->
                @Suppress("UNCHECKED_CAST")
                (t.property("runtimeClasspath") as?
                        org.gradle.api.file.ConfigurableFileCollection)
                    ?.from(project.files(project.provider {
                        val key = if (ctx.type == "server")
                            xyz.wagyourtail.unimined.minecraft.spi
                                .ArtifactKey.SERVER
                        else xyz.wagyourtail.unimined.minecraft.spi
                            .ArtifactKey.CLIENT
                        val a = artifacts.get().artifacts
                        listOfNotNull(
                            (a[key] ?: a[xyz.wagyourtail.unimined
                                .minecraft.spi.ArtifactKey.MERGED])
                                ?.orNull?.asFile)
                    }))
            }
        }
    }
    // unprotect — always-on for Userdev (1.x FG3 parity:
    // `unprotectRuntime = true`). The agent needs tiny mappings
    // with dst ns literally `named` to identify MC classes
    val unprotectJar = project.provider {
        project.configurations.detachedConfiguration(
            project.dependencies.create(
                project.uniminedTools().unprotect.get())
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
            groups = groupsProvider,
            modules = { moduleFiles.get() },
            minecraftClasspathFile = { classpathPathFor(run.name).toString() },
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
            srgMapLzma = {
                val tree = artifacts.get().mappings.get()
                // same boundary as srgToMcpAsSrg, reversed: FML's
                // remapper maps dev (mod-bytecode) refs -> the patched
                // jar's member namespace
                val prodNs =
                    (r.profile as? ForgeProfile.Userdev)?.config
                        ?.patchedNamespace
                        ?: r.descriptor.prodNamespace
                val runtimeDevNs = tree.namespaces.last().name
                exportDevToSrgLzma(tree, runtimeDevNs, prodNs, cacheDir)
            },
            srgToDevMapLzma = {
                val tree = artifacts.get().mappings.get()
                // prod→dev direction: the feather dev jar runs
                // feather-named, but forge/mod reflection probes
                // (`ObfuscationReflectionHelper`) carry srg/mcp member
                // names — FMLDeobfuscatingRemapper translates them
                // only when fed this srg→dev map
                val prodNs =
                    (r.profile as? ForgeProfile.Userdev)?.config
                        ?.patchedNamespace
                        ?: r.descriptor.prodNamespace
                // probes carry `field_*`/`func_*` names — only a
                // srg-side product column answers them
                if (prodNs != "searge") "" else {
                    val runtimeDevNs = tree.namespaces.last().name
                    exportDevToSrgLzma(tree, prodNs, runtimeDevNs, cacheDir,
                        "srg2mcp.srg")
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
            { classpathPathFor(run.name).toString() }
        run.properties["source_roots"] = { ctx.argValue("{source_roots}") }
        run.properties["mcp_mappings"] = { "unimined.stub" }
        // strip template args duplicating options unimined already
        // supplies — jopt-simple rejects a second value for single-arg
        // options (MultipleArgumentsForOptionException on gameDir, then
        // version, at ArgumentHandler.setArgs)
        val owned = setOf(
            "--gameDir", "--version", "--assetsDir",
            "--assetIndex", "--username", "--uuid",
            "--accessToken", "--clientId", "--xuid",
            "--versionType"
        )
        fun stripOwnedArgs(args: List<String>): List<String> {
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
            return out
        }
        when (r.profile) {
            is ForgeProfile.Userdev -> {
                val defaultTweak = "net.minecraftforge.fml.common.launcher." +
                        if (run.type == "server") "FMLServerTweaker" else "FMLTweaker"
                val spec = UserdevRunConfigs.forSide(
                    r.profile.config,
                    run.name, defaultTweak, ctx, prod = run.prod,
                    type = run.type
                ) ?: return@Action
                run.mainClass = spec.mainClass
                run.programArgs.addAll(stripOwnedArgs(spec.args))
                run.jvmArgs.addAll(spec.jvmArgs)
                run.environment.putAll(spec.environment)
                // 1.x FG3 `unprotectRuntime = true` — the javaagent
                // lifts sealed-package/final restrictions on MC
                // classes; mappings zip carries `named` dst ns
                run.javaagents.add(unprotectJar.get().toPath())
                run.jvmArgs.add(
                    "-Dunprotect.mappings=${unprotectMappings.get()}"
                )
            }

            is ForgeProfile.Binpatches -> {
                val profile = r.profile
                // FG2 dev.json ships only client/server launch
                // shapes — keyed on the run's type so renamed runs
                // still qualify and data/test types skip
                if (run.type != "client" && run.type != "server")
                    return@Action
                // FG2 GradleStart/launchwrapper shape — the dev
                // package's dev.json carries the authoritative
                // mainClass/minecraftArguments; jar-content tweak
                // detection is the universal-jar fallback
                run.mainClass = run.mainClass ?: (profile.mainClass
                    ?: "net.minecraft.launchwrapper.Launch")
                if (profile.programArgs.isNotEmpty()) {
                    run.programArgs.addAll(stripOwnedArgs(profile.programArgs))
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
                    ) else if (srgRuntime.get()) listOf(
                        // srg-runtime dev launch — the prod-view jar
                        // ships obf class names, so FML must keep its
                        // runtime deobf transformer (the
                        // `deobfuscatedEnvironment` flag would skip
                        // it and leave srg names unresolved);
                        // DeobfTweakerTx's strip+probe wrap read this
                        // flag at launch, and `srgMap` hands
                        // UniminedFg2 the dev->srg map GradleStart
                        // used to feed FMLDeobfuscatingRemapper
                        "-Dunimined.fg2.runtimeDeobf",
                        "-Dunimined.fg2.srgMap=${ctx.srgMapLzma()}",
                        "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${ctx.srgToMcpAsSrg()}"
                    ) else listOf(
                        "-Dfml.deobfuscatedEnvironment=true",
                        "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${ctx.srgToMcpAsSrg()}"
                    ) + if (ctx.srgToDevMapLzma().isNotEmpty()) listOf(
                        // launch via `Launch`+`FMLTweaker` — no
                        // GradleStart on the path to read `srg.srg-mcp`,
                        // so UniminedFg2 feeds the srg→dev map to
                        // FMLDeobfuscatingRemapper itself — `map`
                        // resolves srg probes to dev jar names
                        "-Dunimined.fg2.srgMap=${ctx.srgToDevMapLzma()}"
                    ) else emptyList()
                )
                if (!run.prod) {
                    // dev launches discover mod classes through
                    // MOD_CLASSES dirs; prod discovers the staged
                    // `mods/` jar — dev dirs would carry
                    // dev-namespace classes the srg runtime can't
                    // resolve
                    run.environment["MOD_CLASSES"] =
                        UserdevRunConfigs.groupsString(groupsProvider())
                }
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
 * through the shared [RunGroupModel] — each project's env binding
 * contributes its own entry at configuration, so reads at execution
 * see every contribution without a `projectsEvaluated` pass. Envs
 * whose project never contributed (plain-java siblings) fall back to
 * a direct source-set read, safe at execution.
 */
internal fun forgeGroups(
    project: Project,
    spec: xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec,
    minecraft: MinecraftExtension
): Map<String, List<String>> {
    val runGroups = xyz.wagyourtail.unimined.api.util.RunGroupModel
        .of(project)
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
        val content =
            "Manifest-Version: 1.0\nAutomatic-Module-Name: $moduleName\n"
        // skip the rewrite when content already matches — this runs
        // inside the `mutated` provider evaluated at config-cache STORE,
        // and writeAtomically's volatile temp sibling would register a
        // fresh tracked path every store → perpetual entry invalidation
        // (RuntimeMappingExports.export applies the same gate)
        if (!java.nio.file.Files.exists(manifest) ||
            java.nio.file.Files.readString(manifest) != content
        ) {
            java.nio.file.Files.createDirectories(manifest.parent)
            manifest.writeAtomically { tmp ->
                java.nio.file.Files.writeString(tmp, content)
            }
        }
        return dir.toAbsolutePath().toString()
    }

    fun dirsFor(projectPath: String, envId: String): List<String> {
        runGroups.contribution(projectPath, envId)
            ?.dirs?.orNull?.let { return it }
        // fallback — the referenced project never contributed (no
        // unimined env binding): read its source set directly. Only
        // reachable at execution, when every project has configured
        val proj = if (projectPath == project.path) project
        else project.rootProject.project(projectPath)
        val sourceSet = proj.extensions
            .findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            ?.findByName(envId) ?: return emptyList()
        return listOfNotNull(sourceSet.output.resourcesDir?.absolutePath) +
                sourceSet.output.classesDirs.files.map { it.absolutePath }
    }

    val ownKey = "${project.path}:${spec.environmentId}"
    // forge-family runtimes group MOD_CLASSES purely by the `%%`
    // label — one label is one exploded mod target / SecureJar union.
    // The mods{} channel names its groups by mod id, so the env's own
    // output must carry the same label or the runtime splits one
    // logical mod into two modfiles: the env-classes half then has no
    // metadata-bearing first root (dropped entirely on modlauncher
    // eras) or double-registers the mod id when both halves carry a
    // descriptor. unimined-mod publishes the resolved id as the
    // `unimined.modId.<env>` extra property; absent it (mod plugin not
    // applied, no id declared) the synthetic env key keeps pre-existing
    // behavior.
    val ownLabel = run {
        val extra = project.extensions.getByType(
            org.gradle.api.plugins.ExtraPropertiesExtension::class.java)
        val key = "unimined.modId.${spec.environmentId}"
        val published = if (extra.has(key)) extra.get(key) else null
        // `orNull`, not `get()` — the published provider is valueless
        // when neither `mod.id` nor a `*.mod.json` resource resolves
        ((published as? Provider<*>)?.orNull as? String)
            ?.takeIf(String::isNotBlank) ?: ownKey
    }
    val groups = linkedMapOf(
        ownLabel to (dirsFor(project.path, spec.environmentId) +
                manifestMemberFor(ownKey)).toMutableList()
    )
    for ((refPath, refEnvId) in spec.inheritedEnvironmentReferences()) {
        // loader identity rides the contribution — absent it (a
        // project without an env binding), fall back to reading the
        // referenced project's MinecraftExtension
        val refMinecraft = (if (refPath == project.path) project
        else project.rootProject.findProject(refPath))
            ?.extensions
            ?.findByType(MinecraftExtension::class.java)
        val inheritedLoader = runGroups
            .contribution(refPath, refEnvId)?.loaderName?.orNull
            ?: refMinecraft?.environments?.findByName(refEnvId)
                ?.loaderSpec?.orNull?.name ?: ""
        val inheritedIsForge = LoaderRegistry
            .resolveBuiltin(inheritedLoader)?.family == "forge"
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
            val own = groups.getValue(ownLabel)
            own.addAll(own.size - 1, dirs)
        }
    }
    // 1.x `detectCombineWithSourceSets` + the non-forge merge: any
    // source set whose output dirs ride this env's runtime classpath
    // (an `implementation(otherSourceSet.output)` dep, e.g. a shared
    // multiversion impl source set) must join a MOD_CLASSES group.
    // Left on `-cp`, FML's bootstrap turns the dir into its own
    // automatic module, which split-packages the mod's package against
    // the group union (`Modules A and B export package C` resolution
    // crash). Forge-family env outputs form their own group; every
    // other detected output merges into the owning group.
    val envSourceSet = project.extensions
        .findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        ?.findByName(spec.environmentId)
    val runtimeClasspath = envSourceSet?.let { ss ->
        project.configurations.findByName(ss.runtimeClasspathConfigurationName)
            ?.files?.map { it.absolutePath }?.toSet()
    } ?: emptySet()
    if (runtimeClasspath.isNotEmpty()) {
        val covered = groups.values.flatten().toMutableSet()
        // dirs a mods{} group already serializes under its own mod-id
        // label must not re-enter as a second group here
        minecraft.environments.findByName(spec.environmentId)
            ?.effectiveModGroups()?.forEach { g ->
                covered += g.launchFiles().map { it.absolutePath }
            }
        for (proj in project.rootProject.allprojects) {
            val container = proj.extensions
                .findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                ?: continue
            for (ss in container.asMap.values) {
                val outDirs = listOfNotNull(
                    ss.output.resourcesDir?.absolutePath) +
                        ss.output.classesDirs.files.map { it.absolutePath }
                if (outDirs.isEmpty() || outDirs.any { it in covered } ||
                    outDirs.none { it in runtimeClasspath }) continue
                covered += outDirs
                val loader = runGroups.contribution(proj.path, ss.name)
                    ?.loaderName?.orNull
                    ?: proj.extensions
                        .findByType(MinecraftExtension::class.java)
                        ?.environments?.findByName(ss.name)
                        ?.loaderSpec?.orNull?.name ?: ""
                if (LoaderRegistry.resolveBuiltin(loader)
                        ?.family == "forge") {
                    groups.getOrPut("${proj.path}:${ss.name}") {
                        mutableListOf()
                    }.addAll(outDirs)
                } else {
                    proj.logger.warn(
                        "[Unimined/ForgeLike] Non-forge " +
                            "${proj.path}:${ss.name} found in forge " +
                            "classpath groups, merging with current " +
                            "($ownLabel), this should've been manually " +
                            "specified with `combineWith`")
                    val own = groups.getValue(ownLabel)
                    own.addAll(own.size - 1, outDirs)
                }
            }
        }
    }
    // a forge-family inherited env forms its own group — it needs
    // its own trailing manifest member too
    groups.entries.forEach { (key, dirs) ->
        if (key != ownLabel &&
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
