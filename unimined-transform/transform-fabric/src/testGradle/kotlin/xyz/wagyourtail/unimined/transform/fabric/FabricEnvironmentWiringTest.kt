package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.EmitArtifactStepSpec
import xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.fabric.fakes.FakeProvider
import java.nio.file.Path

/**
 * Verifies the fabric transform's per-environment contributions: loader
 * dependency injection, mod-source namespace default, contributed JAR
 * pipeline steps, and the run mutator — all gated on `loader("fabric")`.
 */
class FabricEnvironmentWiringTest {

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
            project.plugins.apply("xyz.wagyourtail.unimined.transform.fabric")
        }

    private fun fabricEnv(
        project: org.gradle.api.Project,
        ext: MinecraftExtension
    ) = org.gradle.api.tasks.SourceSetContainer::class.java.let { type ->
        project.extensions.getByType(type).maybeCreate("fabric")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
            env.loader("fabric", "0.16.0")
        }
    }

    @Test
    fun `apply contributes fabric repo and intermediary mod-source default`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // implicit-parent resolution reaches main's spec — it needs a
        // version even though the test only exercises the fabric env
        ext.main.version.set("1.20.1")
        // repo registration is per-selected-loader — an env must
        // declare a fabric-family loader for the maven to land
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
            env.loader("fabric", "0.16.0")
        }
        // per-loader repo registration runs in afterDslEval
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        assertEquals(listOf("intermediary"), ext.loaderModSourceNamespaces["fabric"])
        assertTrue(
            project.repositories.any { (it as? org.gradle.api.artifacts.repositories.MavenArtifactRepository)?.url.toString() == "https://maven.fabricmc.net/" }
        )
    }

    @Test
    fun `fabric env gets loader dep, contributed steps, run mutator`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // implicit-parent resolution reaches main's spec
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = fabricEnv(project, ext)

        val remap = project.extensions.getByType(RemapExtension::class.java)
        val steps = remap.pipelineSteps("fabric", RemapSurface.JAR).get()
        assertEquals(5, steps.size)
        // the prod-jar emit tap rides before the implicit remap core
        assertTrue(steps[0] is EmitArtifactStepSpec)
        assertTrue(steps[1] is RemapStepSpec)
        // auto-appended after remap: embedded-ref fixups (kotlin
        // metadata, mixin configs + refmaps) for the dev->prod hop
        assertTrue(steps[2] is ModArtifactsStepSpec)
        assertTrue(steps[3] is ApplyClassTweakerStepSpec)
        assertTrue(steps[4] is AccessRemapStepSpec)

        // own AW/CTs and dependency-extracted files feed separate
        // collections — dependencies apply through the transitive-only
        // filter
        val awSpec = steps[3] as ApplyClassTweakerStepSpec
        assertTrue(awSpec.classTweakerFiles !== awSpec.dependencyClassTweakerFiles)
        assertEquals(true, env.transitiveAccessWideners.get())

        val transform = project.extensions.getByType(TransformExtension::class.java)
        assertEquals(1, transform.sourceSet("fabric").runMutators.size)

        assertEquals("fabric", env.loaderSpec.orNull?.name)
        assertEquals(
            listOf("intermediary"),
            ext.modSourceNamespaces("fabric").get()
        )
        assertTrue(env.mappingsSpec.defaultSources.isNotEmpty())
        assertNotNull(project.configurations.findByName("fabricModAnalysisFabric"))

        // resolving the analysis view runs the loader-dep hook end-to-end
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.mavenCentral()
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }
        val resolved = project.configurations.getByName("fabricModAnalysisFabric")
            .resolve().map { it.name }
        // the fabric repo is declared first and serves the real loader
        // jar; its embedded fabric-installer.json adds asm + sponge-mixin
        // which ride the same view onto the mod classpath
        assertTrue("fabric-loader-0.16.0.jar" in resolved)
        assertTrue(resolved.any { it.startsWith("sponge-mixin-") })
        assertTrue(resolved.any { it.startsWith("asm-") })
    }

    /** `customGameProvider` adds the loader's skipMcProvider prop to run
     *  configs (1.x patcher flag); unset it adds nothing */
    @Test
    fun `customGameProvider emits the loader's skipMcProvider jvm arg`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = fabricEnv(project, ext)
        // stage the loader jar so the mutator's fabricLoader* view resolves
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { s -> s.artifact() }
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val mutators = transform.sourceSet("fabric").runMutators
        fun runConfig(): xyz.wagyourtail.unimined.api.spec.RunConfigSpec {
            val spec = object : xyz.wagyourtail.unimined.api.spec.RunConfigSpec {
                override val name = "fabricClient"
                override var type = name
                override val prod = false
                override var mainClass: String? = null
                override val javaagents = mutableListOf<java.nio.file.Path>()
                override val jvmArgs = mutableListOf<String>()
                override val programArgs = mutableListOf<String>()
                override val environment = mutableMapOf<String, String>()
                override val properties = mutableMapOf<String, () -> String>()
            }
            // the mutator emits jvmArgs before resolving the env's
            // product (which the fake provider can't produce) — absorb
            // that late failure and assert on the emitted args
            try {
                mutators.forEach { it.execute(spec) }
            } catch (_: Exception) {
            }
            return spec
        }
        assertFalse("-Dfabric.skipMcProvider" in runConfig().jvmArgs)
        env.customGameProvider.set(true)
        assertTrue("-Dfabric.skipMcProvider" in runConfig().jvmArgs)
    }

    @Test
    fun `family members contribute their own loader and intermediary deps`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // implicit-parent resolution reaches main's spec
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        )
        // loader -> (loaderVer, loader GAV, intermediary GAV incl.
        // :v2 classifier) — coords from 1.x MappingsProvider
        val repo = dir.resolve("repo").toFile()
        fun serve(gav: String) {
            // gav = g:a:v or g:a:v:classifier
            val p = gav.split(":")
            val jarDir = repo.resolve("${p[0].replace('.', '/')}/${p[1]}/${p[2]}")
            jarDir.mkdirs()
            val classifier = p.getOrNull(3)?.let { "-$it" } ?: ""
            jarDir.resolve("${p[1]}-${p[2]}$classifier.jar").writeBytes(
                byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
            )
            // artifact()-only metadata establishes the module via the
            // unclassified artifact — classified deps need it served too
            if (p.size > 3) {
                jarDir.resolve("${p[1]}-${p[2]}.jar").writeBytes(
                    byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
                )
            }
        }
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { s -> s.artifact() }
        }
        val cases = listOf(
            FamilyCase("quilt", "0.25.0", "org.quiltmc:quilt-loader:0.25.0", "net.fabricmc:intermediary:1.20.1:v2"),
            FamilyCase("legacyfabric", "0.16.0", "net.fabricmc:fabric-loader:0.16.0", "net.legacyfabric:intermediary:1.20.1:v2"),
            FamilyCase("babric", "0.16.0", "net.fabricmc:fabric-loader:0.16.0", "babric:intermediary-upstream:1.20.1:v2"),
            FamilyCase("ornithe", "0.16.0", "net.fabricmc:fabric-loader:0.16.0", "net.ornithemc:calamus-intermediary-gen2:1.20.1:v2"),
            // flint removed — maven.flintloader.net offline
        )
        for (c in cases) {
            serve(c.loaderGav)
            serve(c.interGav)
            sourceSets.maybeCreate(c.loader)
            ext.sourceSet(c.loader) { env ->
                env.version.set("1.20.1")
                env.loader(c.loader, c.loaderVer)
            }
            val capitalized = c.loader.replaceFirstChar { it.uppercase() }
            val loaderJar = c.loaderGav.split(":")[1] +
                "-${c.loaderGav.split(":")[2]}.jar"
            val resolved = project.configurations
                .getByName("fabricLoader$capitalized").resolve()
                .map { it.name }
            assertTrue(loaderJar in resolved, "${c.loader} loader: $resolved")
            // ornithe's intermediary arrives via the calamus upstream
            // channel, not fabricIntermediary* (no intermediaryCoords)
            if (c.loader == "ornithe") continue
            val interGav = c.interGav.split(":")
            val interJar = "${interGav[1]}-${interGav[2]}-v2.jar"
            val resolvedInter = project.configurations
                .getByName("fabricIntermediary$capitalized").resolve()
                .map { it.name }
            assertTrue(
                interJar in resolvedInter,
                "${c.loader} intermediary: $resolvedInter"
            )
        }
    }

    private data class FamilyCase(
        val loader: String,
        val loaderVer: String,
        val loaderGav: String,
        val interGav: String
    )

    @Test
    fun `contributes the access model with own aw sections`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val remap = project.extensions.getByType(RemapExtension::class.java)
        val envSpec = remap.sourceSet("fabric")
        assertEquals(1, envSpec.accessModelProviders.size)

        // a declared own AW lands as a model section in its own
        // namespace, tagged with the declaring file; dep extraction
        // yields nothing until the analysis view resolves
        val aw = dir.resolve("test.accesswidener").toFile()
        aw.writeText(
            "accessWidener\tv2\tintermediary\n" +
                    "accessible\tclass\ta/b/C"
        )
        env.accessWideners.from(aw)
        env.accessWideners.files.forEach { assertTrue(it.isFile) }

        // resolving the model evaluates the dep-AW provider -> resolves
        // the analysis view; serve the loader jar from a local repo
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.mavenCentral()
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }

        val model = envSpec.accessModelProviders.single().get()
        val section = model.sections.single()
        assertEquals("intermediary", section.namespace)
        val entry = section.entries.single()
        assertEquals("a/b/C", entry.owner)
        assertEquals("test.accesswidener", entry.source)
    }

    @Test
    fun `fabric env registers jar processors and fmj spec context`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet("fabric")
        assertTrue(envSpec.jarProcessors.any { it.name == "mod-provided-javadoc" })
        assertNotNull(envSpec.jarProcessorSpecContext)

        // the environment's mapping spec gains the jar-processors
        // mapping half — mod-provided javadoc enrichment rides it
        assertTrue(
            env.mappingsSpec.postProcessors.any { it.name == "jar-processor-mappings" }
        )
    }

    @Test
    fun `legacy fabric-family loaders get the LWJGL2 substitution`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val cases = mapOf(
            "legacyfabric" to ("org.lwjgl" to "2.9.4+legacyfabric.18"),
            "babric" to ("org.lwjgl" to "2.9.4+legacyfabric.18"),
            "ornithe" to ("org.lwjgl" to "2.9.4+legacyfabric.18"),
            // flint removed — maven.flintloader.net offline
        )
        // spec() resolves each env's own loader+intermediary coords —
        // serve them from a local repo so the test doesn't depend on
        // live mavens
        val repo = dir.resolve("repo").toFile()
        fun serve(gav: String) {
            val p = gav.split(":")
            val jarDir = repo.resolve("${p[0].replace('.', '/')}/${p[1]}/${p[2]}")
            jarDir.mkdirs()
            val classifier = p.getOrNull(3)?.let { "-$it" } ?: ""
            jarDir.resolve("${p[1]}-${p[2]}$classifier.jar").writeBytes(
                byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
            )
            if (p.size > 3) {
                jarDir.resolve("${p[1]}-${p[2]}.jar").writeBytes(
                    byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
                )
            }
        }
        listOf(
            "net.fabricmc:fabric-loader:1.0",
            "net.legacyfabric:intermediary:1.20.1:v2",
            "babric:intermediary-upstream:1.20.1:v2",
            "net.ornithemc:calamus-intermediary-gen2:1.20.1:v2",
        ).forEach(::serve)
        // exclusiveContent is consulted before — and instead of — the
        // remote mavens the loader wiring contributes at evaluate-time
        project.repositories.exclusiveContent { exclusive ->
            exclusive.forRepository {
                project.repositories.maven { m ->
                    m.url = repo.toURI()
                    m.metadataSources { s -> s.artifact() }
                }
            }
            exclusive.filter { it.includeGroupByRegex(".*") }
        }
        // declare all envs before evaluation — post-eval sourceSet()
        // hits a raw afterEvaluate in EnvironmentClasspathWiring
        val envs = cases.keys.associateWith { loader ->
            val env = ext.sourceSet(loader) { e ->
                // asserting only the loader's contributed substitution —
                // the implicit combine would resolve the env's full
                // mapping chain (incl. the intermediary config, which
                // has no resolvable artifact for a fake mc version)
                e.inheritParent.set(false)
                e.version.set("1.20.1")
                e.loader(loader, "1.0")
            }
            project.extensions.getByType(
                org.gradle.api.tasks.SourceSetContainer::class.java
            ).maybeCreate(loader)
            env
        }
        // librarySubstitutions are contributed in afterDslEval
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        for ((loader, rule) in cases) {
            val env = envs.getValue(loader)
            assertEquals(listOf(rule), env.librarySubstitutions,
                "$loader should carry its 1.x LWJGL2 substitution")
            // the rule reaches the provider-facing spec
            assertEquals(listOf(rule), env.spec().librarySubstitutions)
        }
    }

    @Test
    fun `LWJGL substitution is present when product resolution happens before evaluation`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("babric") { e ->
            e.inheritParent.set(false)
            e.version.set("1.20.1")
            e.loader("babric", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("babric")

        // Force product resolution before afterEvaluate. This models user or
        // plugin wiring that probes env.obfuscated during configuration: the
        // resolved ProductArtifacts are memoized, so a substitution deferred
        // to afterDslEval would never reach that artifact view (while the
        // fresh spec() used by natives would still receive it).
        env.obfuscated.get()

        assertEquals(
            listOf("org.lwjgl" to "2.9.4+legacyfabric.18"),
            FakeProvider.resolvedSpecs["babric"]?.librarySubstitutions,
            "the memoized product spec must carry the loader substitution"
        )
    }

    @Test
    fun `modern fabric and quilt get no LWJGL swap`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // implicit-parent resolution reaches main's spec
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = ext.sourceSet("quilt") { e ->
            // asserting quilt's own contributions, not a combine —
            // quilt's intermediary namespace differs from main's
            e.inheritParent.set(false)
            e.version.set("1.20.1")
            e.loader("quilt", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("quilt")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        assertEquals(emptyList<Pair<String, String>>(), env.librarySubstitutions)
        assertEquals(
            emptyList<Pair<String, String>>(),
            env.spec().librarySubstitutions
        )
    }

    @Test
    fun `non-fabric environments get no loader dep`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")

        val modImpl = project.configurations.getByName("modImplementation")
        assertTrue(modImpl.dependencies.isEmpty())
    }

    /** `classpathGroups` capability → declared `mods {}` groups
     *  serialize into `fabric.classPathGroups` (loom `mods {}` /
     *  fabric-loader #585 parity — `::`-separated groups of
     *  `:`-separated paths); `run.loadedMods` picks a subset. */
    @Test
    fun `mods groups serialize into fabric classPathGroups`() {
        val project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = fabricEnv(project, ext)
        env.mods { mods ->
            mods.register("grp") { it.sourceSet("main") }
            mods.register("other") { it.sourceSet("fabric") }
        }
        project.extensions.getByType(
            xyz.wagyourtail.unimined.runs.RunsExtension::class.java)
            .runs.named("fabricServer") {
                it.loadedMods.set(setOf("grp"))
            }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val sep = java.io.File.pathSeparator
        val client = project.tasks.getByName("runFabricClient")
            as xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
        val all = client.systemProperties.get()["fabric.classPathGroups"]
        assertNotNull(all, "runFabricClient must carry the groups prop")
        // unset loadedMods loads every declared group
        assertEquals(2, all!!.split(sep + sep).size)

        val server = project.tasks.getByName("runFabricServer")
            as xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
        val sel = server.systemProperties.get()["fabric.classPathGroups"]
        assertNotNull(sel)
        assertNotNull(sel)
        // subset selection — only `grp` (main output) serializes
        assertEquals(1, sel!!.split(sep + sep).size)
        assertTrue("java${java.io.File.separator}main" in sel)
        assertFalse("fabric${java.io.File.separator}fabric" in sel ||
            "java${java.io.File.separator}fabric" in sel,
            "loadedMods must exclude the unselected group's outputs")
    }

    // ---- real mod-jar fixture ----

    private fun writeClass(
        name: String,
        superName: String = "java/lang/Object",
    ): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, superName, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A real (if tiny) mod jar — `fabric.mod.json` declaring [modId]
     *  plus one stub class. */
    private fun writeModJar(jar: java.io.File, modId: String) {
        jar.parentFile.mkdirs()
        java.util.jar.JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("fabric.mod.json"))
            jos.write(
                """{"schemaVersion":1,"id":"$modId","version":"1.0.0"}"""
                    .encodeToByteArray())
            jos.closeEntry()
            jos.putNextEntry(
                java.util.jar.JarEntry("com/example/TestMod.class"))
            jos.write(writeClass("com/example/TestMod"))
            jos.closeEntry()
        }
    }

    private fun serveModule(repo: java.io.File, gav: String, jar: java.io.File) {
        val p = gav.split(":")
        val moduleDir = repo.resolve(
            "${p[0].replace('.', '/')}/${p[1]}/${p[2]}")
        moduleDir.mkdirs()
        jar.copyTo(moduleDir.resolve("${p[1]}-${p[2]}.jar"))
    }

    /** `mods { } modFiles` jars are launch-classpath content, not just
     *  serialized metadata — the jar joins `run<Name>`'s runtime
     *  classpath and its path serializes into `fabric.classPathGroups`.
     *  Classpath inclusion is asserted on the main env's `runClient`:
     *  group contribution is loader-independent and the fabric env's
     *  runtimeClasspath would need a real remap chain to resolve. */
    @Test
    fun `mods modFiles jar reaches the dev launch classpath and group property`() {
        val project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
        writeModJar(modJar, "testmod")
        ext.main.mods { mods ->
            mods.register("ss") { it.sourceSet("main") }
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        // declare all envs before evaluation — post-eval sourceSet()
        // hits a raw afterEvaluate in EnvironmentClasspathWiring
        fabricEnv(project, ext).mods { mods ->
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val client = project.tasks.getByName("runClient")
            as xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
        // the jar is dev-launch content — the selected-groups provider
        // wired onto runtimeClasspath yields it (full `.files`
        // resolution would pull the product artifact transform, which
        // can't isolate params under ProjectBuilder)
        val wired = client.runtimeClasspath.from.any { src ->
            (src as? org.gradle.api.provider.Provider<*>)
                ?.let { p ->
                    (p.orNull as? Collection<*>)?.contains(modJar) == true
                } == true
        }
        assertTrue(wired,
            "modFiles jar must be wired onto the run runtime classpath")

        // fabric env: the same group serializes into the groups prop —
        // jar-only group → the prop is the jar's path exactly
        val fabricClient = project.tasks.getByName("runFabricClient")
            as xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
        val value = fabricClient.systemProperties
            .get()["fabric.classPathGroups"]
        assertEquals(modJar.absolutePath, value,
            "jar-only group must serialize as the jar's lone path")
    }

    /**
     * Prod runs bypass `sourceSet.runtimeClasspath` — the loader jar,
     * its embedded installer libraries, dep installer libraries, and
     * the relabeled `mappings/mappings.tiny` jar must reach the prod
     * launch classpath via `minecraftLibraries` (the channel prod
     * runs consume; 1.x `minecraftLibraries.extendsFrom(fabric)`
     * parity). Without this `runFabricProdClient` dies on
     * `ClassNotFoundException: KnotClient`.
     */
    @Test
    fun `loader and loader mappings reach minecraftLibraries for prod runs`() {
        val project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = fabricEnv(project, ext)
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val libraries = project.configurations.getByName(
            env.minecraftLibrariesConfigurationName)
        val inherited = libraries.extendsFrom.map { it.name }.toSet()
        assertTrue("fabricLoaderFabric" in inherited,
            "loader jar conf must extend minecraftLibraries: $inherited")
        assertTrue("fabricLoaderLibrariesFabric" in inherited,
            "loader libs conf must extend minecraftLibraries: $inherited")
        assertTrue("fabricDepInstallerLibrariesFabric" in inherited,
            "dep installer libs must extend minecraftLibraries: $inherited")
        // the relabeled mappings jar rides as a self-resolving file
        // dep — neither a module nor a project dependency
        assertTrue(
            libraries.dependencies.any {
                it !is org.gradle.api.artifacts.ModuleDependency &&
                        it !is org.gradle.api.artifacts.ProjectDependency
            },
            "loader mappings jar must be a file dep on minecraftLibraries")
        // and the conf is actually sourced onto the prod run classpath
        val prodClient = project.tasks.getByName("runFabricProdClient")
            as xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
        assertTrue(
            prodClient.runtimeClasspath.from.any { src ->
                (src as? org.gradle.api.provider.Provider<*>)
                    ?.orNull === libraries
            },
            "prod run classpath must source minecraftLibraries")
    }

    /** `modImplementation` deps resolve through `fabricModAnalysis*`
     *  unchanged — the view reads the real jar's `fabric.mod.json`
     *  (`modId`), which is what feeds dep-AW/classTweaker extraction. */
    @Test
    fun `modImplementation jar is discoverable through the analysis view`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        fabricEnv(project, ext)
        val repo = dir.resolve("repo").toFile()
        val modJar = dir.resolve("testmod-1.0.jar").toFile()
        writeModJar(modJar, "testmod")
        serveModule(repo, "com.example:testmod:1.0", modJar)
        // the analysis view also carries the loader jar — serve a stub
        val loaderDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        loaderDir.mkdirs()
        loaderDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18)))
        // exclusiveContent is consulted before — and instead of — the
        // remote mavens the loader wiring contributes at evaluate-time
        project.repositories.exclusiveContent { exclusive ->
            exclusive.forRepository {
                project.repositories.maven { m ->
                    m.url = repo.toURI()
                    m.metadataSources { s -> s.artifact() }
                }
            }
            exclusive.filter { it.includeGroupByRegex(".*") }
        }
        project.dependencies.add(
            "fabricModImplementation", "com.example:testmod:1.0")

        val resolved = project.configurations
            .getByName("fabricModAnalysisFabric").resolve()
        val mod = resolved.single { it.name == "testmod-1.0.jar" }
        // real metadata scan — the analysis reads the fmj id
        assertEquals("testmod",
            xyz.wagyourtail.unimined.transform.fabric.internal
                .FabricModAnalysis.modId(mod.toPath()))
    }

    /** The `gametest()` aux env is the fabric-family JUnit surface —
     *  the loader's `gametestJunit` artifact lands on
     *  `testImplementation`, the `test` task scans the gametest env's
     *  classes, and loom's CompileConfiguration parity carries
     *  `fabric.classPathGroups` (the inherited mods{} selection)
     *  onto it. */
    @Test
    fun `gametest env wires the test task with the junit artifact and classpath groups`() {
        val project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
        writeModJar(modJar, "testmod")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
            env.loader("fabric", "0.16.0")
            // declared before gametest() — aux envs snapshot the
            // parent's groups at creation
            env.mods { mods ->
                mods.register("ss") { it.sourceSet("main") }
                mods.register("jarMod") { it.modFiles(modJar) }
            }
            env.gametest()
        }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        // the gametestJunit artifact lands on testImplementation —
        // `net.fabricmc:fabric-loader-junit:{loader}` substituted
        val junitDep = project.configurations
            .getByName("testImplementation").dependencies
            .firstOrNull { it.name == "fabric-loader-junit" }
        assertNotNull(junitDep,
            "gametestJunit capability must add the junit artifact")
        assertEquals("net.fabricmc", junitDep!!.group)
        assertEquals("0.16.0", junitDep.version)

        val testTask = project.tasks.getByName("test")
            as org.gradle.api.tasks.testing.Test
        // the gametest env's classes are scanned as tests
        val classesDirs = testTask.testClassesDirs.files
        assertTrue(
            classesDirs.any {
                "fabricGametest" in it.absolutePath.replace('\\', '/') },
            "gametest env output must be a test classes dir: $classesDirs")

        // the launch setup is the task's first action — firing it
        // installs the classpath-groups serialization
        runCatching {
            testTask.actions.first().execute(testTask)
        }
        val groups =
            testTask.systemProperties["fabric.classPathGroups"] as? String
        assertNotNull(groups,
            "test must carry fabric.classPathGroups")
        val groupValue = groups!!
        // the parent env's declared groups serialize through the
        // inherited selection (no loadedMods filter on `test`)
        assertTrue(
            "java${java.io.File.separator}main" in groupValue,
            "main-output group's outputs serialize: $groupValue")
        assertTrue(modJar.absolutePath in groupValue,
            "modFiles jar serializes: $groupValue")
    }

}
