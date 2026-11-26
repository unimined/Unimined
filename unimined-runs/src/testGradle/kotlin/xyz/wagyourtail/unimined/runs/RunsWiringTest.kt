package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
import xyz.wagyourtail.unimined.runs.task.PrepareRunTask
import xyz.wagyourtail.unimined.runs.task.RunsReportTask
import java.nio.file.Path

/**
 * Verifies per-environment run wiring: default `client`/`server` run
 * configs materialize `run<Name>` tasks, the shared natives configuration
 * exists, and additional environments get their own prefixed runs.
 */
class RunsWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.runs")
        }

    @Test
    fun `main environment gets runClient and runServer`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val client = project.tasks.findByName("runClient")
        assertNotNull(client)
        assertTrue(client is MinecraftRunTask)
        assertNotNull(project.tasks.findByName("runServer"))

        val runs = project.extensions.getByType(RunsExtension::class.java)
        assertEquals("main", runs.runs.getByName("client").environment.get())
        assertEquals("main", runs.runs.getByName("server").environment.get())
        assertEquals(
            RunSide.SERVER,
            runs.runs.getByName("server").side.get()
        )
        assertNotNull(project.configurations.findByName("minecraftNatives"))
    }

    @Test
    fun `additional environments get prefixed run names`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("fabric")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        assertNotNull(project.tasks.findByName("runFabricClient"))
        assertNotNull(project.tasks.findByName("runFabricServer"))
        assertNotNull(project.configurations.findByName("minecraftFabricNatives"))
    }

    @Test
    fun `custom run config materializes a task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.create("demo") { run ->
            run.username.set("DemoDev")
            run.width.set(854)
            run.height.set(480)
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runDemo")
        assertNotNull(task)
        assertTrue(task is MinecraftRunTask)
        task as MinecraftRunTask
        assertEquals("DemoDev", task.username.get())
        assertEquals(854, task.width.get())
        assertEquals(480, task.height.get())
    }

    @Test
    fun `declared and mutator javaagents reach the run task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val agentFile = dir.resolve("agent.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.runs.getByName("client").javaagents.from(agentFile)

        // transform-mutator path: specs add typed paths, wiring maps
        // them onto `mutatedJavaagents` for -javaagent: rendering
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.javaagents.add(dir.resolve("mutator-agent.jar"))
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertTrue(task.javaagents.files.contains(agentFile))
        assertTrue(
            dir.resolve("mutator-agent.jar").toString() in
                task.mutatedJavaagents.get())
    }

    @Test
    fun `prepare task stages modFiles and renamed dynLibFiles`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            // a product is needed: `modFiles` wiring includes the `jar`
            // task's outputs, whose classifier provider resolves the
            // env's identity-remap state (product-backed)
            env.provider.set("fake")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("prepareRunClient")
        assertNotNull(task)
        task as PrepareRunTask

        val runDir = dir.resolve("run-client")
        task.runDirectory.set(runDir.toFile())

        val mod = dir.resolve("mod.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        val lib = dir.resolve("asm-all-5.2.jar").toFile().apply { writeBytes(byteArrayOf(0)) }
        task.modFiles.from(mod)
        task.stageInto.set("mods")
        task.dynLibFiles.from(lib)
        task.dynLibInto.set("lib")
        task.dynLibRenames.put("asm-all-5.2.jar", "asm-all-4.1.jar")

        task.prepare()

        assertTrue(runDir.resolve("mods/mod.jar").toFile().isFile)
        assertTrue(runDir.resolve("lib/asm-all-4.1.jar").toFile().isFile)
        assertFalse(runDir.resolve("lib/asm-all-5.2.jar").toFile().exists())
        // mods channel must not leak into the lib dir
        assertFalse(runDir.resolve("lib/mod.jar").toFile().exists())
    }

    @Test
    fun `prodClient and prodServer materialize prod run tasks`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        assertTrue(
            project.tasks.findByName("runProdClient") is MinecraftRunTask)
        assertTrue(
            project.tasks.findByName("runProdServer") is MinecraftRunTask)

        val runs = project.extensions.getByType(RunsExtension::class.java)
        assertTrue(runs.runs.getByName("prodClient").prod.get())
        assertTrue(runs.runs.getByName("prodServer").prod.get())
        assertEquals("main", runs.runs.getByName("prodClient").environment.get())
    }

    @Test
    fun `prod run classpath resolves the env production MC view`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val prodConf = project.configurations.getByName("minecraftProd")
        val task =
            project.tasks.findByName("runProdClient") as MinecraftRunTask
        assertTrue(
            task.runtimeClasspath.from.any {
                (it as? org.gradle.api.NamedDomainObjectProvider<*>)
                    ?.orNull === prodConf
            },
            "runProdClient classpath should carry the minecraftProd view"
        )
    }

    @Test
    fun `prod run classpath carries runtime-visible mod buckets only`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        // file deps resolve through the pass-through `*Direct` views
        // without any remap machinery — safe to resolve in tests
        fun stubJar(name: String) = dir.resolve(name).toFile()
            .apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))) }
        val apiJar = stubJar("api-mod.jar")
        val implJar = stubJar("impl-mod.jar")
        val runtimeJar = stubJar("runtime-mod.jar")
        val localJar = stubJar("local-mod.jar")
        val compileJar = stubJar("compile-mod.jar")
        val compileApiJar = stubJar("compile-api-mod.jar")
        ext.main.dependencies {
            it.modApi(project.files(apiJar))
            it.modImplementation(project.files(implJar))
            it.modRuntimeOnly(project.files(runtimeJar))
            it.modLocalRuntime(project.files(localJar))
            it.modCompileOnly(project.files(compileJar))
            it.modCompileOnlyApi(project.files(compileApiJar))
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task =
            project.tasks.findByName("runProdClient") as MinecraftRunTask
        // the mod views ride one provider blob on the prod classpath —
        // resolving it exercises exactly the wiring's configuration set
        // (the pass-through views need no MC artifact, no mappings)
        val resolved = task.runtimeClasspath.from
            .filterIsInstance<org.gradle.api.provider.Provider<*>>()
            // named-configuration providers (`minecraftProd` & co) are
            // not the mod blob — resolving them would materialize the
            // MC artifact pipeline, which can't run under ProjectBuilder
            .filter { it !is org.gradle.api.NamedDomainObjectProvider<*> }
            .flatMap { (it.get() as? org.gradle.api.file.FileCollection)?.files ?: emptySet() }
            .map { it.name }
        for (jar in listOf(apiJar, implJar, runtimeJar, localJar)) {
            assertTrue(jar.name in resolved,
                "${jar.name} must reach the prod run classpath: $resolved")
        }
        for (jar in listOf(compileJar, compileApiJar)) {
            assertFalse(jar.name in resolved,
                "${jar.name} must stay off the prod run classpath: $resolved")
        }
    }

    @Test
    fun `per-run classpath additions reach the launch classpath`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val runs = project.extensions.getByType(RunsExtension::class.java)
        val client = runs.runs.getByName("client")
        val extra = dir.resolve("extra-lib.jar").toFile()
            .apply { writeBytes(byteArrayOf(0)) }
        client.additionalRuntimeClasspath.from(extra)
        project.dependencies.add(
            "runClientDependencies",
            project.files(dir.resolve("dep-lib.jar").toFile()
                .apply { writeBytes(byteArrayOf(0)) })
        )

        // `.files` would resolve the env artifact (absent under
        // ProjectBuilder) — assert the appended `from` elements
        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertTrue(task.runtimeClasspath.from.any {
            it === client.additionalRuntimeClasspath
        }, "additionalRuntimeClasspath should feed the launch classpath")
        assertTrue(task.runtimeClasspath.from.any {
            (it as? org.gradle.api.NamedDomainObjectProvider<*>)
                ?.name == "runClientDependencies"
        }, "run<Name>Dependencies conf should feed the launch classpath")
        // the bucket exists and resolves standalone
        val conf = project.configurations.getByName("runClientDependencies")
        assertFalse(conf.isCanBeConsumed)
        // sibling runs stay clean
        val server = project.tasks.findByName("runServer") as MinecraftRunTask
        assertFalse(server.runtimeClasspath.from.any {
            it === client.additionalRuntimeClasspath
        })
    }

    @Test
    fun `systemProperties and logging config flow to the run task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { run ->
            run.systemProperties.put("dev.flag", "1")
            run.logLevel.set("DEBUG")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertEquals(mapOf("dev.flag" to "1"), task.systemProperties.get())
        assertEquals("DEBUG", task.logLevel.get())
        assertFalse(task.loggingConfigFile.isPresent)
    }

    @Test
    fun `run type decouples the config key from the run name`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("myDatagen") { it.type.set("data") }
        assertEquals("data", runs.runs.getByName("myDatagen").type.get())
        // name-convention parity: unset type resolves to the run name
        assertEquals("client", runs.runs.getByName("client").type.get())
    }

    @Test
    fun `launch script tasks materialize per run plus aggregate`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val script = project.tasks.findByName("genLaunchScriptClient")
        assertTrue(script is MinecraftRunTask)
        script as MinecraftRunTask
        assertTrue(script.emitScripts.get())
        assertEquals("runClient", script.scriptBaseName.get())
        // the real run task still execs
        assertFalse((project.tasks.findByName("runClient")
                as MinecraftRunTask).emitScripts.get())

        val agg = project.tasks.findByName("genLaunchScripts")
        assertNotNull(agg)
        val deps = agg!!.taskDependencies.getDependencies(agg).map { it.name }
        assertTrue("genLaunchScriptClient" in deps)
        assertTrue("genLaunchScriptServer" in deps)
        assertTrue("genLaunchScriptProdClient" in deps)
    }

    @Test
    fun `uniminedRuns reports the declared runs`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val task = project.tasks.findByName("uniminedRuns")
        assertTrue(task is RunsReportTask)
        task as RunsReportTask
        val lines = task.reportLines.get()
        assertTrue(lines.any {
            it.startsWith("runClient") && it.contains("env=main")
        }, "expected a runClient report line, got: $lines")
        assertTrue(lines.any { it.startsWith("runProdServer") })
        assertTrue(lines.any { it.contains("side=SERVER") })
        assertTrue(lines.any { it.contains("prod=true") })
    }

    @Test
    fun `singleInstance defaults false and copies via from`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { it.singleInstance.set(true) }
        runs.create("solo") {
            it.from(runs.runs.named("client"))
        }

        assertTrue(runs.runs.getByName("client").singleInstance.get())
        assertTrue(runs.runs.getByName("solo").singleInstance.get())
        assertFalse(runs.runs.getByName("server").singleInstance.get())
    }

    @Test
    fun `loader override swaps the prod run MC view`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
        }
        // FG2-era forge wiring points prod runs at the raw artifact +
        // FML's runtime deobf — the prod view would skip the
        // authentic obf→srg launch path
        env.prodRunViewName = "minecraftRaw"
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val rawConf = project.configurations.getByName("minecraftRaw")
        val task =
            project.tasks.findByName("runProdClient") as MinecraftRunTask
        assertTrue(
            task.runtimeClasspath.from.any {
                (it as? org.gradle.api.NamedDomainObjectProvider<*>)
                    ?.orNull === rawConf
            },
            "prodRunViewName override should route the prod classpath"
        )
    }

    /** `splitEnvironmentSourceSets` — client-side runs bound to `main`
     *  launch through the split `client` env (the common env's
     *  intersection jar can't run a client); server-side runs stay. */
    @Test
    fun `splitEnvironmentSourceSets redirects client runs onto the split client env`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        ext.splitEnvironmentSourceSets()
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val runs = project.extensions.getByType(RunsExtension::class.java)
        assertEquals("client",
            runs.effectiveEnvironment(project,
                runs.runs.getByName("client")))
        assertEquals("client",
            runs.effectiveEnvironment(project,
                runs.runs.getByName("prodClient")))
        assertEquals("main",
            runs.effectiveEnvironment(project,
                runs.runs.getByName("server")))
        assertEquals("main",
            runs.effectiveEnvironment(project,
                runs.runs.getByName("prodServer")))

        // the materialized task launches on the client source set's
        // classpath — full client artifact + main's output
        val clientSS = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName("client")
        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertTrue(task.runtimeClasspath.from
            .any { it === clientSS.runtimeClasspath },
            "runClient should resolve the split client env's classpath")

        // and stages the combined jar — the packaged mod artifact is
        // main's, the client env's own `clientJar` is client-only
        val prepare = project.tasks.findByName("prepareRunClient")
                as PrepareRunTask
        assertTrue(prepare.modFiles.from.any {
            (it as? org.gradle.api.tasks.TaskProvider<*>)?.name == "jar"
        }, "split client run should stage main's combined jar")
    }

    /** `mods { }` groups serialize through the loader's capability
     *  channels on `run<Name>` — `classpathGroups` → the system
     *  property, `modGroupsEnv` → env-var `id%%dir` entries merged
     *  after whatever the mutator set. `loader()` is applied inside
     *  the env's own configure action, which runs AFTER `wire`'s
     *  `runs.all` pass registers the task — the descriptor lookup is
     *  provider-deferred precisely so this order resolves. */
    @Test
    fun `mods groups serialize onto the classpathGroups prop and modGroupsEnv`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
            e.loader("testfabric", "1.0")
        }
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
        env.mods { mods ->
            mods.register("ss") { it.sourceSet("main") }
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask

        // classpathGroups capability → system property, groups
        // separated by a doubled path separator
        val groupsValue =
            task.systemProperties.get()["fabric.classPathGroups"]
                as? String
        assertNotNull(groupsValue,
            "classpathGroups capability must land as a system property")
        val groups = groupsValue!!.split(
            java.io.File.pathSeparator + java.io.File.pathSeparator)
        assertEquals(2, groups.size,
            "two declared groups serialize as two sections: $groupsValue")
        assertTrue(groups.any {
            "java${java.io.File.separator}main" in it },
            "source-set group's outputs serialize: $groupsValue")
        assertTrue(groups.any { modJar.absolutePath in it },
            "modFiles jar serializes: $groupsValue")

        // group launch files also reach the launch classpath — the
        // selected-groups provider is a `from` element yielding the
        // jar (`.files` would resolve the env artifact, absent under
        // ProjectBuilder)
        assertTrue(task.runtimeClasspath.from.any { src ->
            (src as? org.gradle.api.provider.Provider<*>)
                ?.let { p ->
                    (p.orNull as? Collection<*>)?.contains(modJar) == true
                } == true
        }, "group launch files should feed the launch classpath")

        // modGroupsEnv capability → MOD_CLASSES `id%%dir` entries
        val modClasses = task.mutatedEnvironment.get()["MOD_CLASSES"]
        assertNotNull(modClasses,
            "modGroupsEnv capability must land in the mutated env")
        assertTrue("jarMod%%${modJar.absolutePath}" in modClasses!!,
            "jarMod entry: $modClasses")
        assertTrue(modClasses.contains("ss%%"), "ss entry: $modClasses")
    }

    /** `run.loadedMods` narrows the serialized selection — only the
     *  named group's entries reach the property/env channels. */
    @Test
    fun `loadedMods subset serializes only the selected groups`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
            e.loader("testfabric", "1.0")
        }
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
        env.mods { mods ->
            mods.register("ss") { it.sourceSet("main") }
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { it.loadedMods.set(setOf("ss")) }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask

        val groupsValue =
            task.systemProperties.get()["fabric.classPathGroups"]
                as? String
        assertNotNull(groupsValue)
        assertFalse(modJar.absolutePath in groupsValue!!,
            "unselected group must be absent: $groupsValue")
        assertFalse(groupsValue.contains(
            java.io.File.pathSeparator + java.io.File.pathSeparator),
            "one selected group serializes as a lone section: $groupsValue")

        val modClasses = task.mutatedEnvironment.get()["MOD_CLASSES"]
        assertNotNull(modClasses)
        assertTrue(modClasses!!.contains("ss%%"))
        assertFalse("jarMod%%" in modClasses)
    }

    /** `runs.configFirst` — 1.x ordering parity: the action applies
     *  ahead of subsequently-declared `named`/`create`/
     *  `configureEach` configuration, so later blocks can override
     *  or build on what it set. */
    @Test
    fun `configFirst applies before later-declared run configuration`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.configFirst("client") { it.jvmArgs.add("first") }
        runs.named("client") { it.jvmArgs.add("second") }
        runs.runs.configureEach { it.jvmArgs.add("last") }

        // a configFirst declared after earlier config applies
        // immediately — ahead of subsequent blocks but behind the
        // already-run ones (unlike 1.x's fully-deferred model);
        // "last" precedes "created" because container configureEach
        // callbacks run at realization, before the create action
        runs.create("demo") { it.jvmArgs.add("created") }
        runs.configFirst("demo") { it.jvmArgs.add("first") }
        runs.named("demo") { it.jvmArgs.add("named") }

        assertEquals(listOf("first", "second", "last"),
            runs.runs.getByName("client").jvmArgs.get())
        assertEquals(listOf("last", "created", "first", "named"),
            runs.runs.getByName("demo").jvmArgs.get())
    }

    /** `configFirst` on an absent run creates it — the run then
     *  materializes like any declared run config. */
    @Test
    fun `configFirst creates the run when absent`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.configFirst("custom") { it.jvmArgs.add("x") }
        assertNotNull(runs.runs.findByName("custom"))
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runCustom") as MinecraftRunTask
        assertTrue("x" in task.jvmArgs.get())
    }

    /** `RunConfig.setProperty` — 1.x `${key}` parity: declared values
     *  substitute inside `programArgs`/`jvmArgs`/
     *  `environmentVariables` at launch (exercised through the
     *  `emitScripts` path, which shares the resolved launch). */
    @Test
    fun `setProperty substitutes into args env and jvmArgs at launch`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            // product-backed so launch metadata resolves
            env.provider.set("fake")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { run ->
            run.setProperty("token", "abc123")
            run.setProperty("lazy") { "computed" }
            run.programArgs.add("--token=\${token}")
            run.jvmArgs.add("-Dlazy=\${lazy}")
            run.environmentVariables.put("E", "\${token}-env")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        assertEquals("abc123", task.userLaunchProperties.get()["token"])
        assertEquals("computed", task.userLaunchProperties.get()["lazy"])

        // resolve the launch without forking — `emitScripts` writes
        // the fully-substituted args/env to the run dir; a plain file
        // classpath sidesteps the artifact-transform pipeline (no
        // artifact-producer SPI is registered in this module)
        task.useToolchains.set(false)
        task.requiredJavaVersion.set(8)
        task.runtimeClasspath.setFrom(
            dir.resolve("dev.jar").toFile()
                .apply { writeBytes(byteArrayOf(0)) })
        task.emitScripts.set(true)
        task.launch()

        val sh = dir.resolve("run/main-client/runClient.sh").toFile()
        assertTrue(sh.isFile)
        val body = sh.readText()
        assertTrue("--token=abc123" in body, body)
        assertTrue("-Dlazy=computed" in body, body)
        assertTrue("abc123-env" in body, body)
    }

    /** User `setProperty` entries apply last — they win over the
     *  environment-level defaults and transform-mutator keys (1.x
     *  single-shared-map parity). */
    @Test
    fun `user setProperty wins over env defaults and mutator keys`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            env.provider.set("fake")
        }

        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.properties["k"] = { "mutator" }
        }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { run ->
            run.setProperty("k", "set-by-user")
            run.setProperty("game_dir", "/custom/gamedir")
            run.programArgs.add("--pk=\${k}")
            run.programArgs.add("--gd=\${game_dir}")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        task.useToolchains.set(false)
        task.requiredJavaVersion.set(8)
        task.runtimeClasspath.setFrom(
            dir.resolve("dev.jar").toFile()
                .apply { writeBytes(byteArrayOf(0)) })
        task.emitScripts.set(true)
        task.launch()

        val body = dir.resolve("run/main-client/runClient.sh")
            .toFile().readText()
        assertTrue("--pk=set-by-user" in body, body)
        assertTrue("--gd=/custom/gamedir" in body, body)
        assertFalse("mutator" in body, body)
        // the default `game_dir` (the run dir) must not leak through
        assertFalse(dir.resolve("run/main-client").toString() in body, body)
    }

    /** `beforeRun` — both forms become `run<Name>` prerequisites:
     *  a declared TaskProvider joins `dependsOn`, and each Action
     *  materializes a `preRun<Name><i>` DefaultTask in declaration
     *  order. Actions are execution-time hooks: they must not run while
     *  the run is being wired (the closure twin routes through the same
     *  channel). */
    @Test
    fun `beforeRun wires task deps and execution-time pre tasks`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val buildMod = project.tasks.register("buildMod")
        val runs = project.extensions.getByType(RunsExtension::class.java)
        val executed = mutableListOf<String>()
        runs.named("client") { run ->
            run.beforeRun(buildMod)
            run.beforeRun(org.gradle.api.Action<org.gradle.api.Task> {
                executed += "first pre task"
            })
            run.beforeRun(org.gradle.api.Action<org.gradle.api.Task> {
                executed += "second pre task"
            })
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.getByName("runClient") as MinecraftRunTask

        // action registrations materialize pre<Name><i> in order, but
        // their hooks only run at task execution time
        val pre0 = project.tasks.getByName("preRunClient0")
        val pre1 = project.tasks.getByName("preRunClient1")
        assertEquals(emptyList<String>(), executed)
        pre0.actions.forEach { it.execute(pre0) }
        pre1.actions.forEach { it.execute(pre1) }
        assertEquals(listOf("first pre task", "second pre task"), executed)

        // every declared prerequisite is a run<Name> dependency —
        // inspect `dependsOn` (TaskProvider.getName() doesn't
        // realize); taskDependencies.getDependencies would drag in
        // the run's classpath inputs, which need a live provider
        val depNames = task.dependsOn.map { dep ->
            when (dep) {
                is org.gradle.api.Task -> dep.name
                is org.gradle.api.tasks.TaskProvider<*> -> dep.name
                else -> dep.toString()
            }
        }
        assertTrue("buildMod" in depNames)
        assertTrue("preRunClient0" in depNames)
        assertTrue("preRunClient1" in depNames)
        assertTrue("prepareRunClient" in depNames)
    }

    /** `enabled=false` — the per-run flag and the global
     *  `runs.enabled` both suppress via `onlyIf`, on the launch
     *  task and its `prepare*` companion (1.x task-level `enabled`
     *  parity; tasks still materialize so tooling sees them). */
    @Test
    fun `enabled false suppresses the run and its prepare task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { it.enabled.set(false) }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val run = project.tasks.getByName("runClient") as MinecraftRunTask
        assertFalse((run as org.gradle.api.internal.TaskInternal).getOnlyIf().isSatisfiedBy(run))
        val prepare = project.tasks.getByName("prepareRunClient")
        assertFalse((prepare as org.gradle.api.internal.TaskInternal).getOnlyIf().isSatisfiedBy(prepare))

        // a sibling run is unaffected — the flag is per-run
        val server = project.tasks.getByName("runServer") as MinecraftRunTask
        assertTrue((server as org.gradle.api.internal.TaskInternal).getOnlyIf().isSatisfiedBy(server))
    }

    @Test
    fun `global runs enabled false suppresses every run`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.enabled.set(false)
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        // per-run `enabled` conventions to the extension's — every
        // materialized task inherits the suppression
        for (name in listOf("runClient", "runServer",
            "prepareRunClient", "prepareRunServer")) {
            val task = project.tasks.getByName(name)
            assertFalse((task as org.gradle.api.internal.TaskInternal).getOnlyIf().isSatisfiedBy(task),
                "$name should be suppressed")
        }
        // an explicit per-run override can't resurrect — the
        // convention only flows one direction
    }

    /** `loadedMods(vararg)` — the convenience overload lands the same
     *  `loadedMods.set(...)` narrowing the serialized group
     *  selection (see `loadedMods subset` for the `.set` form). */
    @Test
    fun `loadedMods vararg narrows the serialized groups`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
            e.loader("testfabric", "1.0")
        }
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
        env.mods { mods ->
            mods.register("ss") { it.sourceSet("main") }
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { it.loadedMods("ss") }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        assertEquals(setOf("ss"),
            runs.runs.getByName("client").loadedMods.get())

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        val groupsValue =
            task.systemProperties.get()["fabric.classPathGroups"]
                as? String
        assertNotNull(groupsValue)
        assertFalse(modJar.absolutePath in groupsValue!!,
            "vararg-unselected group must be absent: $groupsValue")
        val modClasses = task.mutatedEnvironment.get()["MOD_CLASSES"]
        assertNotNull(modClasses)
        assertTrue(modClasses!!.contains("ss%%"))
        assertFalse("jarMod%%" in modClasses)
    }

    /** Explicit `unitTest` — forces the JUnit `test<Name>` twin even
     *  without the loader's `gametestJunit` capability (the args file
     *  rides the conventional `fml.junit.argsfile` key), and pulls in
     *  the `test` source set's classes as discovery roots. */
    @Test
    fun `explicit unitTest materializes a Test task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // no loader — the capability-less path still registers the
        // JUnit twin (fml.junit.argsfile is the fallback key)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("client") { it.unitTest.set(true) }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val test = project.tasks.getByName("testClient")
                as org.gradle.api.tasks.testing.Test
        // JUnit-platform discovery replaces the main-class launch
        assertTrue(test.systemProperties.containsKey("fml.junit.argsfile"))
        // `unitTestSources` conventioned to the `test` source set
        val testSS = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName("test")
        assertEquals(testSS.output.classesDirs.files,
            test.testClassesDirs.files)
        // suppression parity with the launch task
        assertTrue((test as org.gradle.api.internal.TaskInternal).getOnlyIf().isSatisfiedBy(test))
    }

    /** `stdin`/`features`/`downloadAssets` — 1.x parity: the three
     *  process-shaping fields route RunConfig → MinecraftRunTask
     *  verbatim; unset runs keep the defaults (no stdin, no rule
     *  features, assets downloaded). */
    @Test
    fun `stdin features and downloadAssets flow to the run task`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("server") { run ->
            run.stdin.set("stop\n")
            run.features.set(setOf("is_demo_user", "has_custom_resolution"))
            run.downloadAssets.set(false)
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runServer") as MinecraftRunTask
        assertEquals("stop\n", task.stdin.get())
        assertEquals(setOf("is_demo_user", "has_custom_resolution"),
            task.features.get())
        assertFalse(task.downloadAssets.get())

        // sibling keeps the defaults — stdin stays unset so the
        // launched process inherits the console stream
        val client = project.tasks.findByName("runClient") as MinecraftRunTask
        assertFalse(client.stdin.isPresent)
        assertTrue(client.features.get().isEmpty())
        assertTrue(client.downloadAssets.get())
    }

    /** `from()` convention-copies the three fields lazily — the
     *  derived run follows the source until it overrides. */
    @Test
    fun `stdin features and downloadAssets copy via from`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }

        val runs = project.extensions.getByType(RunsExtension::class.java)
        runs.named("server") { run ->
            run.stdin.set("stop\n")
            run.features.set(setOf("is_demo_user"))
            run.downloadAssets.set(false)
        }
        runs.create("derived") { it.from(runs.runs.named("server")) }

        val derived = runs.runs.getByName("derived")
        assertEquals("stop\n", derived.stdin.get())
        assertEquals(setOf("is_demo_user"), derived.features.get())
        assertFalse(derived.downloadAssets.get())
    }

    /** `modsDir` capability → `prepare<Run>.stageInto`. On loaders
     *  that discover classpath mods natively (`modGroupsEnv`/
     *  `classpathGroups` — forge-family `ClasspathLocator` +
     *  `MOD_CLASSES`), only `run.modFolderFiles` stages: the project
     *  jar and `*Direct` views would double-register. */
    @Test
    fun `modsDir capability stages only modFolderFiles on classpath-discovery loaders`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { e ->
            e.version.set("1.13.2")
            e.loader("testforge", "1.0")
        }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        val carrier = dir.resolve("carrier.jar").toFile()
            .apply { writeBytes(byteArrayOf(0)) }
        runs.named("client") { it.modFolderFiles.from(carrier) }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val prepare = project.tasks.findByName("prepareRunClient")
            as PrepareRunTask
        assertEquals("mods", prepare.stageInto.get())
        val run = runs.runs.getByName("client")
        assertTrue(
            prepare.modFiles.from.any { it === run.modFolderFiles },
            "modFolderFiles must join the staged set")
        assertFalse(
            prepare.modFiles.from.any {
                (it as? org.gradle.api.tasks.TaskProvider<*>)
                    ?.name == "jar" },
            "classpath-discovered loaders must not stage the " +
                "project jar — it registers through the classpath")
    }

    /** A `modsDir` loader WITHOUT classpath mod discovery keeps the
     *  default staged set — the project's own jar plus `*Direct`
     *  views — alongside `run.modFolderFiles`. */
    @Test
    fun `modsDir capability keeps default staging on folder-only loaders`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
            e.loader("testconv", "1.0")
        }
        val runs = project.extensions.getByType(RunsExtension::class.java)
        val carrier = dir.resolve("carrier.jar").toFile()
            .apply { writeBytes(byteArrayOf(0)) }
        runs.named("client") { it.modFolderFiles.from(carrier) }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val prepare = project.tasks.findByName("prepareRunClient")
            as PrepareRunTask
        assertEquals("mods", prepare.stageInto.get())
        val run = runs.runs.getByName("client")
        assertTrue(
            prepare.modFiles.from.any {
                (it as? org.gradle.api.tasks.TaskProvider<*>)
                    ?.name == "jar" },
            "folder-only loaders still stage the project jar")
        assertTrue(
            prepare.modFiles.from.any { it === run.modFolderFiles },
            "modFolderFiles must join the staged set")
    }

    /** No `modsDir` capability → `stageInto` stays unset and nothing
     *  stages — `modFiles` content is inert. */
    @Test
    fun `no modsDir leaves staging unset`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { e ->
            e.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val prepare = project.tasks.findByName("prepareRunClient")
            as PrepareRunTask
        assertNull(prepare.stageInto.orNull)
    }

    /** useToolchains=false must steer the executable pick, not just
     *  the java-floor check — requiredJavaVersion 99 provisionally
     *  exists nowhere, so a toolchain resolution here always throws;
     *  resolving the build JVM instead proves the task-level override
     *  reaches `javaExecutable` (upstream #226: the macOS failure was
     *  a Java-8 toolchain lookup on machines without one). */
    @Test
    fun `useToolchains false resolves the build JVM without a toolchain lookup`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val task = project.tasks.findByName("runClient") as MinecraftRunTask
        task.useToolchains.set(false)
        task.requiredJavaVersion.set(99)
        val exe = task.javaExecutable.get()
        assertTrue(exe.replace('\\', '/').contains("/bin/java"),
            "toolchains-off must resolve the build JVM, got: $exe")
    }
}
