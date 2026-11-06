package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.TransformExtension
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

    fun wire(project: Project, environment: MinecraftEnvironment) {
        // variant products (liteloader) don't ride the jarMod
        // merge/agent — their artifact is a library dep +
        // versions.json wiring, dispatched via the descriptor's
        // wiringVariant rather than a loader-name check
        val variant = environment.loaderSpec.orNull
            ?.let { LoaderRegistry().resolve(it.name) }?.wiringVariant
        if (variant == "liteloader") {
            LiteLoaderWiring.wire(project, environment)
            return
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
                                val dep = forge.dependencies.firstOrNull()
                                val v = dep?.version ?: dep?.name?.substringAfterLast('-')
                                val mc = environment.version.get()
                                v?.substringAfter("$mc-") ?: ""
                            }
                        )
                    )
            }
            // dyn-lib handling runs for ANY jarmod-family env whose
            // forge zips carry CoreFMLLibraries — self-gates on the
            // class's presence, so covers fg1 and 1.6.4-era FML alike
            wireForgeDynLibs(envSpec, project, environment, forge)
        }
        // modloader-only: ModLoader dev fixes (URI/package patches)
        // — exists-gated internally, so harmless on plain jarmod envs
        val remap = project.extensions.getByType(xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
        remap.sourceSet(envId).contributeStep(
            xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
            project.objects.newInstance(
                xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixesStepSpec::class.java
            ).apply {
                enabled.set(true)
            },
            xyz.wagyourtail.unimined.remap.spi.StepScope.ENVIRONMENT_ARTIFACT
        )
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
            val priority = JarModEnvState.priorityClasspath(envId)
            if (priority.isNotEmpty()) {
                config.jvmArgs += "-Djma.priorityClasspath=" +
                    priority.joinToString(java.io.File.pathSeparator)
            }
            }
        }
    }

    /** FML's dyn-lib rename: the resolved bcprov jar names gain a dot
     *  that `CoreFMLLibraries` never asks for. */
    private val depNameMap = mapOf(
        "bcprov-jdk15on-1.47.jar" to "bcprov-jdk15on-147.jar",
        "bcprov-jdk15on-1.48.jar" to "bcprov-jdk15on-148.jar",
    )

    /**
     * Port of 1.x `FG1MinecraftTransformer.dynLibs`/`resolveDynLibs`:
     * scan `cpw/mods/fml/relauncher/CoreFMLLibraries.class` in the forge
     * zips for the library names FML downloads at runtime; matching
     * names become real dependencies on a `forgeDeps<env>` configuration
     * (extended into the env's `minecraftLibraries`), bundled fmllibs
     * resources are extracted to `localCache/fmllibs` and
     * added as file deps, and a run mutator copies the resolved files
     * to `runDir/lib` under FML's expected names.
     */
    private fun wireForgeDynLibs(
        envSpec: xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec,
        project: Project,
        environment: MinecraftEnvironment,
        forge: org.gradle.api.artifacts.Configuration
    ) {
        val envId = environment.environmentId
        val forgeDeps = project.configurations.maybeCreate(
            if (envId == "main") "forgeDeps" else
                "forgeDeps${envId.replaceFirstChar { it.uppercaseChar() }}"
        ).apply { isCanBeConsumed = false }
        project.configurations.matching {
            it.name == environment.minecraftLibrariesConfigurationName
        }.all {
            // `forge` itself goes on too — the era zip ships FML
            // (cpw.mods.fml.*) that mods compile against; forgeDeps
            // only carries the zip's bundled *libraries* (argo/asm).
            it.extendsFrom(forgeDeps, forge)
        }

        // maven-resolvable fmllibs — the checksum patch rewrites
        // CoreFMLLibraries.checksums to whatever we ship, so the
        // resolved jars needn't be FML's exact hosted copies
        val mavenDeps = mapOf(
            "argo-2.25.jar" to "net.sourceforge.argo:argo:2.25",
            "guava-12.0.1.jar" to "com.google.guava:guava:12.0.1",
            "guava-14.0-rc3.jar" to "com.google.guava:guava:14.0-rc3",
            "asm-all-4.0.jar" to "org.ow2.asm:asm-all:4.0",
            "asm-all-4.1.jar" to "org.ow2.asm:asm-all:4.1",
            "scala-library.jar" to "org.scala-lang:scala-library:2.9.1-1",
            "bcprov-jdk15on-148.jar" to "org.bouncycastle:bcprov-jdk15on:1.48",
            "bcprov-jdk15on-147.jar" to "org.bouncycastle:bcprov-jdk15on:1.47",
            // argo-small is FML's slim build of argo 3.2 — never
            // published; the full jar is a superset and the checksum
            // patch covers the hash
            "argo-small-3.2.jar" to "net.sourceforge.argo:argo:3.2",
        )
        // generated content — no maven home; and its joined.srg is
        // NOT the src bundle's conf/joined.srg (1586245 vs 1425098
        // bytes), so it can't be regenerated. Stays bundled.
        val bundled = setOf("deobfuscation_data_1.5.2.zip")

        val wanted = project.provider {
            val out = mutableSetOf<String>()
            for (zip in forge.files.filter { it.isFile }) {
                java.util.zip.ZipFile(zip).use { z ->
                    z.getEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
                        ?.let { e -> z.getInputStream(e).use { out += dynLibs(it) } }
                }
            }
            out.toSet()
        }

        // `wanted.get()` resolves `forge` — defer to afterDslEval so a
        // user-declared `forge(...)` dep (the 1.x `forge { loader }`
        // model) lands in `dependencies {}` first; otherwise the config
        // is observed at bind time and `dependencies { forge(...) }`
        // fails "cannot mutate after resolved"
        project.afterDslEval {
            val names = wanted.get()
            val asm52 = JarModEnvState.useAsm52(envId)
            val wantedAsm = names.firstOrNull { it.startsWith("asm-all-") }
            if (wantedAsm != null) {
                // 1.x libraryFilter — FML supplies its own asm-all, so the
                // MC-declared ow2 libs must not reach the classpath
                environment.libraryFilters += { it.group != "org.ow2.asm" }
            }
            // fmllibs jars whose FML-pinned sha1 no maven coord
            // matches (FML's own repackaged asm-all-4.0, etc.) —
            // fetched as exact copies from the wag flat fmllibs
            // store so the checksum passes unpatched
            val wagFmlLibs = setOf("asm-all-4.0.jar")
            for ((name, coord) in mavenDeps) {
                if (name !in names) continue
                when {
                    asm52 && name.startsWith("asm-all-") ->
                        project.dependencies.add(forgeDeps.name, "org.ow2.asm:asm-all:5.2")
                    name in wagFmlLibs ->
                        project.dependencies.add(forgeDeps.name,
                            project.files(project.provider {
                                resolveFmlLib(project, name).toFile() }))
                    else ->
                        project.dependencies.add(forgeDeps.name, coord)
                }
            }
            val fmllibs = project.layout.buildDirectory.dir("unimined/fmllibs/$envId")
            for (name in bundled) {
                if (name !in names) continue
                if (asm52 && name.startsWith("asm-all-")) {
                    project.dependencies.add(forgeDeps.name, "org.ow2.asm:asm-all:5.2")
                    continue
                }
                val target = fmllibs.get().asFile.resolve(name)
                if (!target.exists()) {
                    target.parentFile.mkdirs()
                    JarModEnvironmentWiring::class.java
                        .getResourceAsStream("/fmllibs/$name")!!
                        .use { it.copyTo(target.outputStream()) }
                }
                project.dependencies.add(forgeDeps.name, project.files(target))
            }

            // resolved jars land in lib/ under FML's expected names —
            // asm-5.2 is copied under the wanted asm-all-4.x name
            val renames = depNameMap +
                (if (asm52 && wantedAsm != null)
                    mapOf("asm-all-5.2.jar" to wantedAsm) else emptyMap()) +
                (if ("argo-small-3.2.jar" in names)
                    mapOf("argo-3.2.jar" to "argo-small-3.2.jar") else emptyMap())

            // checksum patch + run mutator also depend on the scan
            wireChecksumPatch(envSpec, project, envId, forgeDeps, renames, names)
        }
    }

    /** fg1 checksum-patch processor + run mutators — the pieces of
     *  `wireForgeDynLibs` that consume the scanned dyn-lib names. */
    private fun wireChecksumPatch(
        envSpec: xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec,
        project: Project,
        envId: String,
        forgeDeps: org.gradle.api.artifacts.Configuration,
        renames: Map<String, String>,
        names: Set<String>
    ) {
        // patch CoreFMLLibraries.checksums to the SHA1s of the jars we
        // actually ship (lazily — resolves forgeDeps at spec build)
        envSpec.jarProcessors +=
            xyz.wagyourtail.unimined.transform.jarmod.internal.fg1
                .FMLChecksumPatchProcessor {
                    forgeDeps.resolve()
                        .associate { (renames[it.name] ?: it.name) to it }
                        .filterKeys { it in names }
                }

        val transform = project.extensions.getByType(TransformExtension::class.java)
        transform.sourceSet(envId).runMutators += org.gradle.api.Action { config ->
            config.jvmArgs += "-Dminecraft.applet.TargetDirectory=${'$'}{game_dir}"
            // per-run lib sums — CoreFMLLibraries loads from the
            // (tx-patched) forge zip; the spliced helper reads this
            // property and rewrites `checksums[]` to match what we stage
            val sums = forgeDeps.resolve()
                .associate { (renames[it.name] ?: it.name) to it }
                .filterKeys { it in names }
            if (sums.isNotEmpty()) {
                val prop = sums.entries.joinToString(";") { (n, f) ->
                    "$n=${sha1Hex(f)}"
                }
                config.jvmArgs += "-Dunimined.fg1.libSums=$prop"
            }
        }
        // prepareTaskMutators stage the run dir — copy forgeDeps into
        // runDirectory/lib before the run task executes (FML's dyn-lib dir)
        transform.sourceSet(envId).prepareTaskMutators += org.gradle.api.Action { task ->
            task.doFirst { t ->
                val dir = (t.property("runDirectory") as? org.gradle.api.file.DirectoryProperty)
                    ?.get()?.asFile ?: return@doFirst
                val lib = java.io.File(dir, "lib").apply { mkdirs() }
                for (f in forgeDeps.resolve().filter { it.isFile && it.extension != "pom" })
                    f.copyTo(lib.resolve(renames[f.name] ?: f.name), overwrite = true)
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

    /** Flat-file fetch for the exact FML-pinned fmllibs jars that
     *  maven can't supply a checksum-matching copy of (FML's own
     *  repackaged asm-all-4.0, etc.) — hosted as a flat store at
     *  `releases/fmllibs/<name>` on the wag maven (1.x shipped these
     *  as bundled `/fmllibs/` resources). Honors --offline/refresh. */
    private fun resolveFmlLib(
        project: Project,
        name: String
    ): java.nio.file.Path {
        val dest = UniminedCache.global(project)
            .resolve("fmllibs").resolve(name)
        val refresh = project.gradle.startParameter.isRefreshDependencies
        if (java.nio.file.Files.exists(dest) && !refresh) return dest
        if (project.gradle.startParameter.isOffline) {
            if (java.nio.file.Files.exists(dest)) return dest
            throw org.gradle.api.GradleException(
                "offline and no cached fmllib $name under $dest")
        }
        java.nio.file.Files.createDirectories(dest.parent)
        try {
            java.net.URI.create(
                "https://maven.wagyourtail.xyz/releases/fmllibs/$name")
                .toURL().openStream().use {
                java.nio.file.Files.copy(it, dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            throw org.gradle.api.GradleException(
                "could not fetch fmllib $name from the wag fmllibs store", e)
        }
        return dest
    }

    private fun sha1Hex(f: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Read the dyn-lib names out of `CoreFMLLibraries.<clinit>` —
     *  the String-array LDCs stored into `libraries`
     *  (1.x `FG1MinecraftTransformer.getDynLibs`). */
    private fun dynLibs(stream: java.io.InputStream): Set<String> {
        val cn = org.objectweb.asm.tree.ClassNode().also {
            org.objectweb.asm.ClassReader(stream).accept(it, 0)
        }
        val clInit = cn.methods.firstOrNull { it.name == "<clinit>" } ?: return emptySet()
        val out = mutableSetOf<String>()
        var inArray = false
        for (insn in clInit.instructions) {
            if (insn is org.objectweb.asm.tree.TypeInsnNode &&
                insn.opcode == org.objectweb.asm.Opcodes.ANEWARRAY &&
                insn.desc == "java/lang/String") inArray = true
            if (inArray && insn is org.objectweb.asm.tree.LdcInsnNode && insn.cst is String)
                out.add(insn.cst as String)
            if (insn is org.objectweb.asm.tree.FieldInsnNode &&
                insn.opcode == org.objectweb.asm.Opcodes.PUTSTATIC) {
                if (insn.name == "libraries") break
                inArray = false; out.clear()
            }
        }
        return out
    }

}
