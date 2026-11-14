package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.jarmod.FG1DynLibWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo

/**
 * Environment wiring for `loader("jarmod", ...)` environments —
 * 1.x `JarModMinecraftTransformer`/`JarModAgentMinecraftTransformer`
 * split into a merge step (jar contents -> dev MC jar) plus, in a
 * follow-up, JarModAgent runtime wiring.
 *
 * Two per-env configurations, matching 1.x:
 *  - `jarMod<env>`: user-declared jars merged into the Minecraft jar
 *    (any dependency type — maven coords, project(), files(), ...).
 *  - `jarModAgent<env>`: the JarModAgent artifact itself, resolved for
 *    compile-time transforms/run wiring (phase b).
 */
internal object JarModEnvironmentWiring {

    private const val WIRED_FLAG = "unimined.jarmod.loaderWired"

    /** Create the `jarMod*` configurations — called at env
     *  registration (before the script's `dependencies {}` block
     *  evaluates), unlike [wire] which runs at env binding once the
     *  loader is known. */
    fun createConfigurations(project: Project, environment: MinecraftEnvironment) {
        val envId = environment.environmentId
        project.configurations.maybeCreate(
            if (envId == "main") "jarMod" else
                "jarMod${envId.replaceFirstChar { it.uppercaseChar() }}"
        ).apply { isCanBeConsumed = false }
        project.configurations.maybeCreate(
            if (envId == "main") "forge" else
                "forge${envId.replaceFirstChar { it.uppercaseChar() }}"
        ).apply { isCanBeConsumed = false }
        project.configurations.maybeCreate(
            if (envId == "main") "jarModAgent" else
                "jarModAgent${envId.replaceFirstChar { it.uppercaseChar() }}"
        ).apply { isCanBeConsumed = false }
    }

    /**
     * Wire an environment — idempotent across the bound-time and
     * transform-claim invocations. The umbrella plugin applies this
     * plugin eagerly, so the bound-time call lands *before* the
     * `minecraft {}` DSL populates `loaderSpec`; the loader-dependent
     * dispatch is deferred to afterEvaluate. Claim-time calls arrive
     * post-evaluation and execute immediately.
     */
    fun wire(project: Project, environment: MinecraftEnvironment) {
        val extra = environment.extensions.extraProperties
        if (extra.has(WIRED_FLAG)) return
        extra.set(WIRED_FLAG, true)
        project.afterDslEval { wireLoader(project, environment) }
    }

    private fun wireLoader(project: Project, environment: MinecraftEnvironment) {
        // variant products (liteloader, rift) don't ride the jarMod
        // merge/agent — their artifact is a library dep +
        // versions.json wiring, dispatched via the descriptor's
        // wiringVariant rather than a loader-name check
        val variant = environment.loaderSpec.orNull
            ?.let { LoaderRegistry().resolve(it.name) }?.wiringVariant
        when (variant) {
            "liteloader" -> {
                LiteLoaderWiring.wire(project, environment)
                return
            }
            "rift" -> {
                RiftWiring.wire(project, environment)
                return
            }
        }
        // envs can now bind inside the same environments.all pass that
        // declared them — before the plugin-level createConfigurations
        // hook gets its turn; it is maybeCreate-based/idempotent
        createConfigurations(project, environment)
        val envId = environment.environmentId
        // 1.x `yarn()` `requires("intermediary")`: jarmod-family prod
        // namespace is `official`, so a yarn-family mapping chain needs
        // the `official -> intermediary` hop to produce the prod
        // artifact (remapJar/remapSourcesJar). Contribute it lazily —
        // only when a yarn source is declared without an explicit
        // intermediary, matching fabric's implicit upstream hop.
        environment.mappingsSpec.deferredDefaultSources += {
            val declared = sources
            val hasYarn = declared.any {
                it is xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
                    .Source.Maven && ":yarn:" in it.notation
            }
            val hasIntermediary = declared.any {
                it is xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
                    .Source.Maven &&
                    it.notation.startsWith("net.fabricmc:intermediary")
            }
            if (hasYarn && !hasIntermediary && environment.obfuscated.get())
                collectDeclared { intermediary() }
            else emptyList()
        }
        val jarMod = project.configurations.getByName(
            if (envId == "main") "jarMod" else
                "jarMod${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        val jarModAgent = project.configurations.getByName(
            if (envId == "main") "jarModAgent" else
                "jarModAgent${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(envId)
        val forge = project.configurations.findByName(
            if (envId == "main") "forge" else
                "forge${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        // jarModAgent resolves at execution, after `loaderSpec` is set —
        // `withDependencies` reads it live then. (`forge` resolves
        // during configuration via the transform fingerprint, before
        // the env's loader() runs — it's a user-declared dep config,
        // not loaderSpec-derived, so it needs no hook here.)
        jarModAgent.withDependencies { deps ->
            environment.loaderSpec.orNull?.let { spec ->
                // fg1's loader version is FORGE's (the user-facing loader
                // artifact merges via `forge`) — jarmod-agent is internal
                // and pins to a fixed version, not spec.version
                val agentVersion = if (LoaderRegistry().resolve(spec.name)?.id == "fg1")
                    "0.1.5" else spec.version
                deps.add(
                    project.dependencies.create(
                        "xyz.wagyourtail.unimined:jarmod-agent:$agentVersion:all"
                    )
                )
            }
        }
        // pre-1.3 forge ships per-side artifacts (`:client`/`:server`
        // classifier) whose obf namespaces are disjoint — merging the
        // wrong side's zip silently overwrites same-named classes
        // (jarmod entries win collisions). 1.x semantics: only the env
        // side's artifact merges; unmarked names (universal/custom) pass.
        val sideForge = forge?.let { f ->
            project.files(project.provider {
                val other = when (environment.side.get()) {
                    xyz.wagyourtail.unimined.api.spec.Side.CLIENT -> "-server."
                    xyz.wagyourtail.unimined.api.spec.Side.SERVER -> "-client."
                    xyz.wagyourtail.unimined.api.spec.Side.MERGED -> null
                }
                f.files.filter { file ->
                    val drop = other != null &&
                        file.name.lowercase().contains(other)
                    if (drop) project.logger.lifecycle(
                        "[JarMod] skipping ${file.name}: wrong side " +
                                "for ${environment.side.get()} environment"
                    )
                    !drop
                }
            })
        }
        // one merge instance for both configs — a second same-named
        // processor would dedupe and silently drop the forge zips.
        // forge zips come FIRST: fg1-era forge ships FML-patched
        // ModLoader/BaseMod that must win over plain jarmod deps
        // (modloader's unpatched copies break FML's mod container +
        // dev-time URI fix gating).
        // Merge inputs ride `jarmod:merge` env-artifacts, not the
        // processor's constructor list — `.files` here would snapshot
        // `forge`/`jarMod` while `loaderSpec` is still empty (wiring
        // predates the env DSL's `loader(...)` call), permanently
        // emptying the merge. The binding resolves at transform
        // fingerprinting, after loader-contributed deps land.
        val priorMerge = envSpec.envArtifactBindings["jarmod:merge"]
        envSpec.envArtifactBindings["jarmod:merge"] = {
            (if (sideForge != null) sideForge + jarMod else jarMod)
                .files.map { it.toPath() } + priorMerge?.invoke().orEmpty()
        }
        // obf->named package split turns formerly package-legal
        // protected accesses into cross-package violations — widen them
        envSpec.jarProcessors += AccessWideningProcessor()
        envSpec.jarProcessors += JarModMergeProcessor(
            deleteMetaInf = { JarModEnvState.deleteMetaInf(envId) }
        )

        // fg1: forge zips merge into the jar too; its ATs come out
        // and ride apply-class-tweaker; FML's dynamic libraries are
        // resolved into `forgeDeps` and land in runDir/lib
        // loaderSpec is populated at bound time; spec() would eagerly
        // query version, which the DSL may not have set yet
        val isFg1 = environment.loaderSpec.orNull?.name == "fg1"
        if (forge != null) {
            // fg1's era forge zip is pom-less — there's no module
            // descriptor on files.minecraftforge.net, so it can't ride
            // a maven-coord dep on `forge`. Resolve the loader's
            // `loaderCoords` through unimined's own downloader and
            // contribute it to `forge` as a file dep. Deferred into a
            // provider so `loaderSpec` is populated and the download
            // runs when `forge` resolves, not at configuration time.
            project.dependencies.add(forge.name, project.files(project.provider {
                val spec = environment.loaderSpec.orNull
                val d = spec?.let { LoaderRegistry().resolve(it.name) }
                if (spec?.name == "fg1" && d != null) {
                    val mc = environment.version.get()
                    // pre-1.3 forge ships per-side `:client`/`:server`
                    // zips (no `:universal` until 1.3) — pick by env
                    // side; merged dev needs both halves merged like the
                    // base MC client+server merge
                    val minor = Regex("^1\\.(\\d+)").find(mc)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    val classifiers = when {
                        minor == null || minor >= 3 -> listOf("universal")
                        environment.side.get() == xyz.wagyourtail.unimined.api.spec.Side.SERVER -> listOf("server")
                        environment.side.get() == xyz.wagyourtail.unimined.api.spec.Side.MERGED -> listOf("client", "server")
                        else -> listOf("client")
                    }
                    classifiers.map { clf ->
                        resolveLoaderArtifact(
                            project,
                            "net.minecraftforge:forge:$mc-${spec.version}:$clf@zip",
                            d.repos
                        ).toFile()
                    }
                } else emptyList()
            }))
            // must run after the forge merge — it normalizes the era
            // `*_at.cfg` entries the merge just injected (and strips
            // bundled dyn-lib dirs)
            envSpec.jarProcessors += xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FG1FixForgeProcessor()
            val atDir = project.layout.buildDirectory.dir("unimined/fg1-ats/$envId")
            environment.accessTransformers.from(project.provider {
                val dir = atDir.get().asFile
                val out = mutableListOf<java.io.File>()
                for (zip in sideForge!!.files.filter { it.isFile }) {
                    java.util.zip.ZipFile(zip).use { z ->
                        for (name in listOf("forge_at.cfg", "fml_at.cfg")) {
                            z.getEntry(name)?.let { e ->
                                val target = dir.resolve("${zip.nameWithoutExtension}-$name")
                                target.parentFile.mkdirs()
                                target.bufferedWriter().use { w ->
                                    z.getInputStream(e).bufferedReader().forEachLine { line ->
                                        w.write(line)
                                        w.newLine()
                                    }
                                }
                                out += target
                            }
                        }
                    }
                }
                out
            })

            if (isFg1) {
                // 1.x FG1MinecraftTransformer.beforeMappingsResolve —
                // pre-1.7 forge bundles searge csvs in the src zip, so
                // auto mcp is disabled and forgeBuiltinMCP is injected
                // unless the user declared customSearge. canCombine
                // gates to mc>=1.3 (merged jars only).
                // the forgeBuiltinMCP hop resolves `de.oceanlabs.mcp:
                // mcp_legacy` — served by the Forge maven repo entry
                xyz.wagyourtail.unimined.mapping.UniminedRepos(project)
                    .minecraftForge.ensure()
                val canCombine = environment.version.map {
                    xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
                        .compare(it, "1.3") >= 0
                }.orElse(false)
                environment.mappingsSpec.defaultSources +=
                    xyz.wagyourtail.unimined.mapping.MappingsSpecImpl.Source.Builtin(
                        project.provider {
                            if (!JarModEnvState.customSearge(envId) && canCombine.get())
                                "forgeBuiltinMCP" else "official"
                        },
                        mapOf(
                            "version" to project.provider {
                                // fg1's loader spec carries the bare
                                // forge version (`loader("fg1", v)`);
                                // scraping `forge` deps breaks on the
                                // files() dep the wiring itself adds
                                environment.loaderSpec.orNull?.version ?: ""
                            }
                        )
                    )
            }
            // dyn-lib handling runs for ANY jarmod-family env whose
            // forge zips carry CoreFMLLibraries — self-gates on the
            // class's presence, so covers fg1 and 1.6.4-era FML alike.
            // `forge` itself also joins `minecraftLibraries` — the era
            // zip ships FML (cpw.mods.fml.*) that mods compile against;
            // forgeDeps only carries the zip's bundled *libraries*
            FG1DynLibWiring.wire(
                project, environment, envSpec,
                project.provider { forge.files.filter { it.isFile }.toSet() },
                compileClasspathJars = listOf(forge),
                useAsm52 = JarModEnvState.useAsm52(envId),
            )
        }
        val remap = project.extensions.getByType(xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
        // dev-side access apply — user-declared AT/AW/CT docs widen
        // the artifact `compileJava` sees, mirroring the loader
        // family wirings (the runtime agent still applies them at
        // launch). `accessTransformers` also carries the FG1 forge-zip
        // extraction above — those `*_at.cfg`s are obf/legacy-named
        // installer files, never dev-applied: filter by the known
        // extraction dir. User headerless ATs are dev-named.
        val jarmodApplySpec = project.objects.newInstance(
            xyz.wagyourtail.unimined.remapper.access
                .ApplyClassTweakerStepSpec::class.java)
        jarmodApplySpec.classTweakerFiles.from(
            environment.accessWideners,
            environment.generatedAccessFiles,
            project.provider {
                val fg1Ats = project.layout.buildDirectory
                    .dir("unimined/fg1-ats/$envId").get().asFile.toPath()
                environment.accessTransformers.files.filter {
                    !it.toPath().startsWith(fg1Ats) }
            })
        jarmodApplySpec.headerlessNamespace.set(environment.devNamespace)
        remap.sourceSet(envId).contributeStep(
            xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
            jarmodApplySpec
        )
        // modloader-only: ModLoader dev fixes (URI/package patches)
        // — exists-gated internally, so harmless on plain jarmod envs
        remap.sourceSet(envId).contributeStep(
            xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
            project.objects.newInstance(
                xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixesStepSpec::class.java
            ).apply {
                enabled.set(true)
            },
            xyz.wagyourtail.unimined.remap.spi.StepScope.ENVIRONMENT_ARTIFACT
        )
        // 1.x `JarModAgentPatcher.compiletimeTransforms` — forked
        // JarModAgent main pass over the mod jar before the namespace
        // remap. Pre-step so it runs on the original-namespace input;
        // ModArtifactOnly keeps it out of the MC dev-artifact pipeline
        // and dep views never consume pre-steps. Deferred — the flag
        // and transforms list aren't final until after DSL eval.
        remap.sourceSet(envId).contributeDeferredSteps(project.provider {
            if (environment.loaderSpec.orNull?.name != "jarmod" ||
                !JarModEnvState.compiletimeTransforms(envId) ||
                JarModEnvState.transforms(envId).isEmpty()
            ) emptyList()
            else listOf(
                xyz.wagyourtail.unimined.remap.spi.DeferredStepContribution(
                    xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
                    project.objects.newInstance(
                        JmaCompileTransformStepSpec::class.java
                    ).apply {
                        transforms.set(JarModEnvState.transforms(envId))
                        agentJar.set(jarModAgent.resolve()
                            .first { it.extension == "jar" }.absolutePath)
                        debug.set(true)
                    },
                    pre = true
                )
            )
        })
        // JarModAgent on the run classpath: -javaagent + system props
        // (1.x JarModAgentMinecraftTransformer run config). Jarmod-
        // loader envs only — fg1/modloader reuse this wiring as a
        // substrate but run their own transform systems (FML/CoreMod);
        // an attached agent would suppress their transformers.
        // `loaderSpec` is still empty at wiring time (this plugin is
        // applied by the aggregate before the env DSL body runs), so
        // the loader-name check lives inside the mutator — evaluated
        // when the run spec is built, after `loader()` has run.
        envSpec.runMutators += org.gradle.api.Action { config ->
        if (environment.loaderSpec.orNull?.name == "jarmod") {
            val jmaFile = jarModAgent.resolve().first { it.extension == "jar" }
            config.javaagents.add(jmaFile.toPath())
            val transforms = JarModEnvState.transforms(envId)
            if (transforms.isNotEmpty()) {
                config.jvmArgs += "-Djma.transformers=" +
                    transforms.joinToString(java.io.File.pathSeparator)
            }
            val refmaps = JarModEnvState.refmaps(envId)
            if (refmaps.isNotEmpty()) {
                config.jvmArgs += "-Djma.refmaps=" +
                    refmaps.joinToString(java.io.File.pathSeparator)
            }
            val initializers = JarModEnvState.initializers(envId)
            if (initializers.isNotEmpty()) {
                config.jvmArgs += "-Djma.initializers=" +
                    initializers.joinToString(java.io.File.pathSeparator)
            }
            JarModEnvState.failStrategy(envId)?.let {
                config.jvmArgs += "-Djma.failStrategy=$it"
            }
            if (JarModEnvState.debug(envId)) {
                config.jvmArgs += "-Djma.debug=true"
            }
            // jma.priorityClasspath is the agent's OVERRIDE classpath:
            // classes here win over -cp and carry the transform/refmap
            // resources `jma.transformers` resolves. It must NOT contain
            // the MC artifact or libraries — a class that exists ONLY on
            // priority (as everything would if the whole run classpath
            // were listed) loads raw with NO transform applied, which
            // silently disables the agent. Correct contents:
            //   dev  = the env's own sourceSet outputs (mod classes may
            //          override MC classes; resources carry *.transform)
            //   prod = remapJar output + *Direct mod-dep views (the
            //          remapped env jar + shipped mod jars)
            // MC stays on -cp where `hasRuntimePatches` routes it
            // through transformerManager.
            config.properties["jma_priority_classpath"] = {
                val dslEntries = JarModEnvState.priorityClasspath(envId)
                val files = if (config.prod) {
                    val remapJar = project.tasks.findByName(
                        if (envId == "main") "remapJar"
                        else "remap${
                            envId.replaceFirstChar(Char::uppercase)
                        }Jar")
                    buildList {
                        project.configurations.findByName(
                            "${environment.modImplementationConfigurationName}Direct"
                        )?.let { addAll(it.files) }
                        project.configurations.findByName(
                            "${environment.modRuntimeOnlyConfigurationName}Direct"
                        )?.let { addAll(it.files) }
                        remapJar?.outputs?.files?.let { addAll(it) }
                    }
                } else {
                    project.extensions.getByType(
                        org.gradle.api.tasks.SourceSetContainer::class.java
                    ).findByName(envId)?.output?.let {
                        it.classesDirs.files + setOfNotNull(it.resourcesDir)
                    } ?: emptySet()
                }
                (dslEntries + files.map { it.absolutePath })
                    .joinToString(java.io.File.pathSeparator)
            }
            config.jvmArgs += "-Djma.priorityClasspath=" +
                "\${jma_priority_classpath}"
            }
        }
        // JarModAgent-* manifest attrs on the env jar — the built-
        // time counterpart of the run sysprops: `modArtifacts` gates
        // its ClassTransform refmap emission on -Refmaps, and users
        // dropping the jar into a real mods folder get the attrs read
        // by the agent's boostrapModJar path. NOTE the agent splits
        // manifest attrs on ' ' — NOT the sysprops' pathSeparator.
        // Emitted at task-config so the values ride the config cache.
        if (environment.loaderSpec.orNull?.name == "jarmod") {
            project.extensions.getByType(
                org.gradle.api.tasks.SourceSetContainer::class.java
            ).findByName(envId)?.let { src ->
                project.tasks.named(
                    src.jarTaskName, org.gradle.jvm.tasks.Jar::class.java
                ).configure { jar ->
                    jar.manifest.attributes(buildMap {
                        JarModEnvState.transforms(envId)
                            .takeIf { it.isNotEmpty() }
                            ?.let { put("JarModAgent-Transforms", it.joinToString(" ")) }
                        JarModEnvState.refmaps(envId)
                            .takeIf { it.isNotEmpty() }
                            ?.let { put("JarModAgent-Refmaps", it.joinToString(" ")) }
                        JarModEnvState.initializers(envId)
                            .takeIf { it.isNotEmpty() }
                            ?.let { put("JarModAgent-Initializers", it.joinToString(" ")) }
                    })
                }
            }
        }
    }

    /** Resolve a `g:a:v[:c][@e]` coord to a local file via direct repo
     *  download — FG1-era forge artifacts publish no pom/module
     *  descriptor, so they can't ride a Gradle Configuration. Tries
     *  each [LoaderRepo.Maven] base in order; caches under the global
     *  unimined cache and honors `--offline` / `--refresh-dependencies`. */
    private fun resolveLoaderArtifact(
        project: Project,
        notation: String,
        repos: List<LoaderRepo>
    ): java.nio.file.Path {
        val coords = notation.substringBefore('@').split(':')
        val (g, a, v) = coords
        val classifier = coords.getOrNull(3)
        val ext = notation.substringAfter('@', "jar")
        val file = "$a-$v" + (classifier?.let { "-$it" } ?: "") + ".$ext"
        val rel = "${g.replace('.', '/')}/$a/$v/$file"
        val dest = UniminedCache.global(project)
            .resolve("loader-artifacts/$g/$a/$v").resolve(file)
        val refresh = project.gradle.startParameter.isRefreshDependencies
        if (java.nio.file.Files.exists(dest) && !refresh)
            return patchFg1ForgeZip(project, dest)
        if (project.gradle.startParameter.isOffline) {
            if (java.nio.file.Files.exists(dest))
                return patchFg1ForgeZip(project, dest)
            throw org.gradle.api.GradleException(
                "offline and no cached loader artifact $notation under $dest")
        }
        java.nio.file.Files.createDirectories(dest.parent)
        var err: Exception? = null
        for (base in repos.filterIsInstance<LoaderRepo.Maven>().map { it.url }) {
            try {
                java.net.URI.create("$base/$rel").toURL().openStream().use {
                    java.nio.file.Files.copy(it, dest,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                return patchFg1ForgeZip(project, dest)
            } catch (e: Exception) {
                err = e
            }
        }
        throw org.gradle.api.GradleException(
            "could not resolve loader artifact $notation from " +
                    repos.filterIsInstance<LoaderRepo.Maven>().map { it.url }, err)
    }

    /** fg1-era forge zips carry `CoreFMLLibraries` on the run
     *  classpath untouched — the checksum tx can't reach them through
     *  the product-jar pipeline, so the tx + helper are spliced into
     *  the zip itself here. Sums are NOT baked in: per-env sha1s ride
     *  `-Dunimined.fg1.libSums` at launch, so the patched zip stays a
     *  benign no-op for consumers that never set the property. */
    private fun patchFg1ForgeZip(project: Project, zip: java.nio.file.Path): java.nio.file.Path {
        val marker = zip.resolveSibling(zip.fileName.toString() + ".unimined-patched")
        if (java.nio.file.Files.exists(marker)) return zip
        try {
            val hasFml = java.util.zip.ZipFile(zip.toFile()).use {
                it.getEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class") != null
            }
            if (!hasFml) return zip
            java.nio.file.FileSystems.newFileSystem(zip).use { fs ->
                xyz.wagyourtail.unimined.transform.internal.classtransform
                    .ClassTransformJarFix.fix(fs, mapOf(
                        "xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.CoreFMLLibrariesChecksumTx" to
                            listOf("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
                    ), helpers = listOf(
                        "xyz/wagyourtail/unimined/transform/jarmod/internal/fg1/rt/UniminedFg1ChecksumFix.class"
                    ))
            }
            java.nio.file.Files.writeString(marker, "1")
        } catch (e: Exception) {
            project.logger.warn(
                "fg1 checksum tx patch on {} failed: {}", zip, e.message)
        }
        return zip
    }
}
