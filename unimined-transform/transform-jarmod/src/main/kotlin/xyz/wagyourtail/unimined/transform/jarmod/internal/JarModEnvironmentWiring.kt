package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

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
        val envId = environment.environmentId
        val jarMod = project.configurations.getByName(
            if (envId == "main") "jarMod" else
                "jarMod${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        val jarModAgent = project.configurations.getByName(
            if (envId == "main") "jarModAgent" else
                "jarModAgent${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
        environment.loaderSpec.orNull?.let { spec ->
            project.dependencies.add(
                jarModAgent.name, "xyz.wagyourtail.unimined:jarmod-agent:${spec.version}:all"
            )
        }

        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet(envId)
        val forge = project.configurations.findByName(
            if (envId == "main") "forge" else
                "forge${envId.replaceFirstChar { it.uppercaseChar() }}"
        )
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
        envSpec.jarProcessors += JarModMergeProcessor(
            (if (sideForge != null) sideForge + jarMod else jarMod).files.map { it.toPath() },
            { JarModEnvState.deleteMetaInf(envId) }
        )

        // fg1: forge zips merge into the jar too; its ATs come out
        // and ride apply-class-tweaker; FML's dynamic libraries are
        // resolved into `forgeDeps` and land in runDir/lib
        // loaderSpec is populated at bound time; spec() would eagerly
        // query version, which the DSL may not have set yet
        val isFg1 = environment.loaderSpec.orNull?.name == "fg1"
        if (forge != null) {
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
        if (environment.loaderSpec.orNull?.name == "jarmod") {
            envSpec.runMutators += org.gradle.api.Action { config ->
            val jmaFile = jarModAgent.resolve().first { it.extension == "jar" }
            config.jvmArgs += "-javaagent:${jmaFile.absolutePath}"
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
        }.all { it.extendsFrom(forgeDeps) }

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

        // dependencies{} has already run by env binding — populate
        // forgeDeps from the scanned names eagerly
        val names = wanted.get()
        val asm52 = JarModEnvState.useAsm52(envId)
        val wantedAsm = names.firstOrNull { it.startsWith("asm-all-") }
        if (wantedAsm != null) {
            // 1.x libraryFilter — FML supplies its own asm-all, so the
            // MC-declared ow2 libs must not reach the classpath
            environment.libraryFilters += { it.group != "org.ow2.asm" }
        }
        for ((name, coord) in mavenDeps) {
            if (name !in names) continue
            project.dependencies.add(
                forgeDeps.name,
                if (asm52 && name.startsWith("asm-all-")) "org.ow2.asm:asm-all:5.2" else coord
            )
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
