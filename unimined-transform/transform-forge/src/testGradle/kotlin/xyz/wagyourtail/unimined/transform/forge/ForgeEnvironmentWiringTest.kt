package xyz.wagyourtail.unimined.transform.forge

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Verifies the forge transform's descriptor-driven wiring for every
 * registered forge-family product — `forge`, `lexforge`, `neoforge`,
 * `cleanroom` — through the real env-binding path: repo registration,
 * provider convention, loader-artifact resolution + installer probe,
 * prod-namespace convention, and the forge-named dev jar base.
 */
class ForgeEnvironmentWiringTest {

    @TempDir
    lateinit var dir: Path

    /** Groups already pinned to the local repo — `exclusiveContent`
     *  rewrites every other repo's content descriptor, which is only
     *  legal while no repo has resolved yet; a repeat declaration for
     *  the same group after a resolution throws. */
    private val exclusiveGroups = mutableSetOf<String>()

    private fun pinLocalRepo(group: String) {
        if (!exclusiveGroups.add(group)) return
        project.repositories.exclusiveContent { exclusive ->
            exclusive.forRepository {
                project.repositories.maven {
                    it.url = dir.resolve("repo").toFile().toURI()
                    // POM_AND_ARTIFACT parity with the real forge
                    // maven — the pom anchors the module so a missing
                    // classifier 404s the artifact, not the module
                    it.metadataSources { s -> s.mavenPom(); s.artifact() }
                }
            }
            exclusive.filter { it.includeGroup(group) }
        }
    }

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.transform.forge")
        }

    /** Serve a probe-valid userdev jar for [coords] (`g:a:v`) from a
     *  local maven repo — `config.json` + `binpatches.pack.lzma` is the
     *  minimum `ForgeInstallerProbe` needs for `Userdev`. */
    private fun serveUserdev(coords: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        // obfNamespace reads the config's `mcp` coord — neoform-family
        // → mojmap, mcp_config → searge
        val mcp = if (g == "net.neoforged")
            "net.neoforged:neoform:$v@zip"
        else "de.oceanlabs.mcp:mcp_config:1.20.1@zip"
        JarOutputStream(jarDir.resolve("$a-$v-userdev.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("config.json"))
            out.write("""{"mcp": "$mcp"}""".toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("binpatches.pack.lzma"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        // exclusiveContent keeps resolution local — the plugin-
        // contributed forge maven precedes this repo otherwise and
        // the test would probe a live artifact (repo rule + flaky)
        pinLocalRepo(g)
    }

    /** Serve the FG2-on-userdev3 shape (`1.12.2/14.23.x`): universal
     *  carries `binpatches.pack.lzma`; `userdev3` carries the FG3-style
     *  config. */
    private fun serveUserdev3(coords: String, configJson: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        JarOutputStream(jarDir.resolve("$a-$v-universal.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("binpatches.pack.lzma"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        JarOutputStream(jarDir.resolve("$a-$v-userdev3.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("config.json"))
            out.write(configJson.toByteArray())
            out.closeEntry()
        }
        pinLocalRepo(g)
    }

    /** Serve a probe-valid FG1-era `universal` zip for [coords]
     *  (`g:a:v`) — a `net/minecraft/` class entry is all
     *  `ForgeInstallerProbe` needs for `ZipPatches`. */
    private fun serveZipPatches(coords: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        // ship the patch zip under both extensions — `:universal`
        // (jar) resolves before `@zip` in the cascade; either probes
        // `ZipPatches`
        for (ext in listOf("jar", "zip")) {
            JarOutputStream(
                jarDir.resolve("$a-$v-universal.$ext").outputStream()
            ).use { out ->
                out.putNextEntry(JarEntry("net/minecraft/Dummy.class"))
                out.write(byteArrayOf(0))
                out.closeEntry()
            }
        }
        // exclusiveContent keeps the loader-resolution cascade local —
        // the earlier `:userdev`/`:universal` candidates must 404
        // against this repo, never the live forge maven
        pinLocalRepo(g)
    }

    /** Serve the `:installer` classifier for [coords] (`g:a:v`) —
     *  carries the given `install_profile.json` text plus the
     *  `data/<side>.lzma` entries split-side binpatching reads. */
    private fun serveInstaller(coords: String, installProfile: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        JarOutputStream(
            jarDir.resolve("$a-$v-installer.jar").outputStream()
        ).use { out ->
            out.putNextEntry(JarEntry("install_profile.json"))
            out.write(installProfile.toByteArray())
            out.closeEntry()
            for (side in listOf("client", "server")) {
                out.putNextEntry(JarEntry("data/$side.lzma"))
                out.write("x".toByteArray())
                out.closeEntry()
            }
        }
        pinLocalRepo(g)
    }

    private lateinit var project: org.gradle.api.Project

    private fun forgeEnv(
        ext: MinecraftExtension,
        product: String,
        mc: String,
        loaderVer: String,
        envId: String = product
    ) = ext.sourceSet(envId) { env ->
        env.version.set(mc)
        env.loader(product, loaderVer)
    }.also {
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate(envId)
    }

    @Test
    fun `non-forge envs get no forge conventions`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("notforge") { e ->
            e.version.set("1.20.1")
            e.loader("fabric", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("notforge")
        // non-forge envs fall back to the generic
        // `minecraft-<env>-<version>` base name (ebaf7bb8) — forge
        // conventions contribute the loader-specific name only
        assertEquals("minecraft-notforge-1.20.1", env.artifactBaseName.orNull)
        // 2.0.0 regression — the loader-resolution provider is absent
        // on non-forge envs and `addAll(absent)` poisoned the whole
        // property (convention AND explicit set both ignored), so
        // ProductArtifactTransform isolation failed with
        // "Property 'sideMarkers' doesn't have a configured value"
        assertTrue(env.sideMarkers.isPresent,
            "non-forge env must keep sideMarkers resolvable")
        assertTrue(env.sideMarkers.get().isEmpty())
    }

    @Test
    fun `apply registers all forge-family mavens`() {
        project = project()
        val urls = project.repositories
            .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
            .map { it.url.toString() }
        assertTrue(urls.any { it.startsWith("https://maven.minecraftforge.net") })
        assertTrue(urls.any { it.startsWith("https://maven.neoforged.net") })
        assertTrue(urls.any { it.startsWith("https://repo.cleanroommc.com") })
        assertTrue(urls.any { it.startsWith("https://maven.arcseekers.com") })
    }

    @Test
    fun `every forge-family product resolves loader artifact and conventions`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // implicit-parent resolution reaches main's spec
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        // product -> (mc, loaderVer, expected artifact base, expected prodNs)
        val products = listOf(
            Product("forge", "1.20.1", "47.3.0", "forge-1.20.1-47.3.0", "searge"),
            Product("lexforge", "1.20.1", "47.3.0", "forge-1.20.1-47.3.0", "searge"),
            // 1.x convention: the loader arg is the version tail —
            // "77" -> neoforge:21.1.77 for mc 1.21.1
            Product("neoforge", "1.21.1", "77", "neoforge-21.1.77", "mojmap"),
            Product("cleanroom", "1.12.2", "0.2.0-alpha", "cleanroom-0.2.0-alpha", "searge"),
        )
        // serve every product first — exclusiveContent pins must all
        // land before the first resolution (content descriptors freeze
        // once a repo has been used)
        for (p in products) serveUserdev(when (p.id) {
            "neoforge" -> "net.neoforged:neoforge:21.1.${p.loaderVer}"
            "cleanroom" -> "com.cleanroommc:cleanroom:${p.loaderVer}"
            else -> "net.minecraftforge:forge:${p.mc}-${p.loaderVer}"
        })
        for ((i, p) in products.withIndex()) {
            val env = forgeEnv(ext, p.id, p.mc, p.loaderVer)

            assertEquals(p.id, env.loaderSpec.orNull?.name, "${p.id} loaderSpec")

            // artifact base keeps the loader coord needle for FML's
            // classpath probe
            assertEquals(
                p.base, env.artifactBaseName.get(),
                "${p.id} artifactBaseName"
            )
            // prod-namespace convention comes from the descriptor —
            // evaluating it resolves + probes the loader jar
            assertEquals(
                listOf(p.prodNs), ext.modSourceNamespaces(p.id).get(),
                "${p.id} modSourceNamespaces"
            )
        }
    }

    @Test
    fun `userdev production namespace follows runtime era rather than obf input`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        serveUserdev("net.minecraftforge:forge:1.21.1-52.0.0")
        serveUserdev3(
            "net.minecraftforge:forge:1.12.2-14.23.5.2860",
            """{"mcp":"de.oceanlabs.mcp:mcp_config:1.12.2@zip","notchObf":true}"""
        )

        forgeEnv(ext, "lexforge", "1.21.1", "52.0.0", "lexforgeModern")
        forgeEnv(ext, "lexforge", "1.12.2", "14.23.5.2860", "lexforge12userdev3")

        // Modern LexForge runs mojmap at production runtime even though
        // its userdev still names mcp_config rather than neoform.
        assertEquals(
            listOf("mojmap"), ext.modSourceNamespaces("lexforgeModern").get())
        // userdev3-on-FG2 marks the notch obf *input* (`official`), but
        // FML runtime-deobfs production mods at the searge boundary.
        assertEquals(
            listOf("searge"), ext.modSourceNamespaces("lexforge12userdev3").get())
    }

    /** Cleanroom swaps vanilla 1.12.2's platform libs for modern forks
     *  via its userdev `libraries` — the env's provider-lib filter must
     *  drop the vanilla copies it shadows
     *  (1.x `CleanroomMinecraftTransformer.vanillaExcludesSet`). */
    @Test
    fun `cleanroom filters vanilla platform libs`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.12.2")
        ext.main.provider.set("fake")
        serveUserdev("com.cleanroommc:cleanroom:0.5.0-alpha")
        val env = forgeEnv(ext, "cleanroom", "1.12.2", "0.5.0-alpha")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        fun keep(coord: String) =
            env.libraryFilters.all { it(project.dependencies.create(coord)) }
        assertTrue(env.libraryFilters.isNotEmpty(),
            "cleanroom should contribute libraryFilters")
        // vanilla-1.12.2 set cleanroom replaces — dropped
        assertFalse(keep("com.mojang:patchy:1.3.9"))
        assertFalse(keep("oshi-project:oshi-core:1.1"))
        assertFalse(keep("com.ibm.icu:icu4j-core-mojang:51.2"))
        assertFalse(keep("net.java.jutils:jutils:1.0.0"))
        assertFalse(keep("io.netty:netty-all:4.1.9.Final"))
        assertFalse(keep("net.java.dev.jna:platform:3.4.0"))
        assertFalse(keep("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"))
        assertFalse(keep("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209"))
        // cleanroom's modern replacements stay
        assertTrue(keep("org.lwjgl:lwjgl:3.4.1"))
        assertTrue(keep("com.cleanroommc:lwjglxx:1.1.17"))
        assertTrue(keep("com.google.guava:guava:21.0"))
    }

    /** `runTypes` capability → `runData`/`runJunit`/`runGameTestServer`
     *  materialize from the descriptor with no `runs.create` (FG's
     *  per-userdev-key task parity); each carries its userdev key as
     *  the run's `type` so the mutator resolves `runs.<type>`. */
    @Test
    fun `runTypes materialize declared run configs`() {
        project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        // run materialization is post-DSL — loaderSpec isn't visible
        // when environments.all fires
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val runs = project.extensions.getByType(
            xyz.wagyourtail.unimined.runs.RunsExtension::class.java)
        for (name in listOf(
            "data", "clientData", "serverData",
            "gameTestServer", "junit")) {
            val runName = "forge${name.replaceFirstChar(Char::uppercase)}"
            assertNotNull(
                project.tasks.findByName("run${
                    runName.replaceFirstChar(Char::uppercase)}"),
                "run$runName should materialize without runs.create")
            assertEquals(name, runs.runs.getByName(runName).type.get())
            assertEquals(
                "forge", runs.runs.getByName(runName).environment.get())
        }
    }

    /** `gametestJunit` capability → a junit-typed run additionally
     *  materializes a Gradle `Test` task (`test<Name>`) per NG
     *  `isJUnit`/`unitTest` — non-junit types stay run-task only. */
    @Test
    fun `junit run type materializes a Test task`() {
        project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val testTask = project.tasks.findByName("testForgeJunit")
        assertNotNull(testTask, "testForgeJunit should materialize")
        assertTrue(testTask is org.gradle.api.tasks.testing.Test)
        assertNull(
            project.tasks.findByName("testForgeData"),
            "non-junit run types must not materialize Test tasks")
        // isJUnit convention fires on the type, not just materialized runs
        assertTrue(
            project.extensions.getByType(
                xyz.wagyourtail.unimined.runs.RunsExtension::class.java)
                .runs.getByName("forgeJunit").unitTest.get())
    }

    /** `runTaskContributions` → a `write<Env>RunClasspath<Run>` writer
     *  registers per run and both the `run*` and junit `test*` tasks
     *  depend on it (they share the writer — same run name, one file).
     *  The pre-convention `writeRunClasspath<Env><Run>` name remains a
     *  deprecated alias. */
    @Test
    fun `classpath writer materializes per run and backs run and test tasks`() {
        project = project().also {
            it.plugins.apply("xyz.wagyourtail.unimined.runs")
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val writer = project.tasks.findByName("writeForgeRunClasspathForgeJunit")
        assertNotNull(writer, "per-run classpath writer should materialize")
        assertTrue(writer is
                xyz.wagyourtail.unimined.transform.forge.task.WriteRunClasspath)
        // pre-convention name stays reachable as a deprecated alias
        val legacyAlias = project.tasks.findByName(
            "writeRunClasspathForgeForgeJunit")
        assertNotNull(legacyAlias,
            "deprecated writeRunClasspath<Env><Run> alias should register")
        // declared dependsOn entries only — resolving the dependency
        // set would realize the artifact/mappings providers
        for (taskName in listOf("runForgeJunit", "testForgeJunit")) {
            val task = project.tasks.findByName(taskName)
            assertNotNull(task, "$taskName should materialize")
            assertTrue(
                task!!.dependsOn.any {
                    it.toString().contains("writeForgeRunClasspathForgeJunit")
                },
                "$taskName should depend on the classpath writer")
        }
    }

    /** 1.x `NeoForgedMinecraftTransformer.configureRemapJar`: prod on
     *  real neoforge (>1.20.1) runs at mojmap — the searge-era refmap
     *  is suppressed on the auto-appended ModArtifactsStepSpec, but
     *  1.20.1's `net.neoforged:forge` artifact (searge prod) keeps it. */
    @Test
    fun `neoforge refmap suppression honors the 1_20_1 carve-out`() {
        fun emitRefmapFor(mc: String, ver: String, coords: String): Boolean {
            project = project()
            val ext = project.extensions
                .getByType(MinecraftExtension::class.java)
            ext.main.version.set(mc)
            ext.main.provider.set("fake")
            serveUserdev(coords)
            forgeEnv(ext, "neoforge", mc, ver)
            (project as org.gradle.api.internal.project.ProjectInternal)
                .evaluate()
            val spec = xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec()
            project.extensions.getByType(
                xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
                .sourceSet("neoforge")
                .modArtifactsConventions.forEach { it.execute(spec) }
            return spec.emitRefmap
        }
        assertFalse(emitRefmapFor(
            "1.21.1", "77", "net.neoforged:neoforge:21.1.77"))
        assertTrue(emitRefmapFor(
            "1.20.1", "47.1.79", "net.neoforged:forge:1.20.1-47.1.79"))
    }

    /** LaunchWrapper-era forge coremods implement `IClassTransformer` and
     *  carry descriptor literals used as bytecode-construction inputs. The
     *  forge environment opts dependency mod artifacts into that contract-
     *  scoped channel while the global modArtifacts default remains off.
     *  The opt-in is correctness-critical, so it lands in the
     *  correctness-convention list (pre-seeds even explicit `modArtifacts
     *  {}` declarations) — not the convenience list. */
    @Test
    fun `forge mod artifacts enable launchwrapper transformer ldc rewrite`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.7.10")
        ext.main.provider.set("fake")
        serveUserdev("net.minecraftforge:forge:1.7.10-10.13.4.1614")
        forgeEnv(ext, "forge", "1.7.10", "10.13.4.1614")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val spec = xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec()
        val env = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet("forge")
        assertTrue(env.modArtifactsConventions.isEmpty() ||
            env.modArtifactsConventions.none { action ->
                val probe = xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec()
                action.execute(probe)
                probe.remapLaunchwrapperTransformerLdcs
            },
            "the ldc correctness pre-seed must not ride the convenience list — " +
            "it would be dropped by explicit modArtifacts declarations")
        env.modArtifactsCorrectnessConventions.forEach { it.execute(spec) }
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "forge dependency artifacts must opt into transformer-scoped ldc rewriting")
        assertTrue(spec.remapClassNameLdcs,
            "the transformer opt-in activates the derived ldc pass input")
        assertTrue(spec.classNameLdcClasses.isEmpty(),
            "the forge wiring must not resurrect an unsafe universal/explicit class list")
    }

    /** Invoke the forge mapping-hop contribution directly — the lambda
     *  is registered last on `deferredDefaultSources`; evaluating it
     *  realizes `resolved` (which resolves + probes the served
     *  artifact) without pulling the full `effectiveSources` chain. */
    private fun contributedChannels(
        env: xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
    ) = env.mappingsSpec.deferredDefaultSources.last()
        .invoke(env.mappingsSpec)
        .filterIsInstance<MappingsSpecImpl.Source.Builtin>()

    /** B6.8 — `loader("forge")` on an FG1-era artifact probes
     *  `ZipPatches`; that path must contribute the era's
     *  `forgeBuiltinMCP` hop (jarmod's `loader("fg1")` parity), not
     *  just the `official` identity. */
    @Test
    fun `zipPatches profile contributes forgeBuiltinMCP mapping default`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.5.2")
        ext.main.provider.set("fake")
        serveZipPatches("net.minecraftforge:forge:1.5.2-7.8.1.738")
        val env = forgeEnv(ext, "forge", "1.5.2", "7.8.1.738")
        val builtin = contributedChannels(env).single()
        assertEquals("forgeBuiltinMCP", builtin.nameProvider.get())
        assertEquals("7.8.1.738", builtin.params["version"]!!.invoke())
    }

    /** A user-declared searge-family builtin suppresses the bundled
     *  hop — declaring your own chain is the forge path's
     *  `customSearge` opt-out. */
    @Test
    fun `zipPatches profile falls back to official when searge declared`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.5.2")
        ext.main.provider.set("fake")
        serveZipPatches("net.minecraftforge:forge:1.5.2-7.8.1.738")
        val env = forgeEnv(ext, "forge", "1.5.2", "7.8.1.738")
        env.mappingsSpec.searge()
        val builtin = contributedChannels(env).single()
        assertEquals("official", builtin.nameProvider.get())
    }

    /** Regression guard — the `Userdev` path still contributes the
     *  profile's obf-namespace hop (searge at the config's mcp_config
     *  version). */
    @Test
    fun `userdev profile contributes profile obf-namespace hop`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        serveUserdev("net.minecraftforge:forge:1.20.1-47.3.0")
        val env = forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        val builtin = contributedChannels(env).single()
        assertEquals("searge", builtin.nameProvider.get())
        assertEquals("1.20.1", builtin.params["version"]!!.invoke())
    }

    /** D2 — `jsCoremodRemap` is read inside the deferred
     *  `forge:jsCoremodRemap` step contribution, so a provider-set
     *  value stays unevaluated through configuration and materializes
     *  with the pipeline. Opting out drops the step on a userdev env
     *  where it would otherwise contribute. */
    @Test
    fun `jsCoremodRemap provider resolves at contribution materialization`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        serveUserdev("net.minecraftforge:forge:1.20.1-47.3.0")
        val env = forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        var evaluations = 0
        (env as org.gradle.api.plugins.ExtensionAware).extensions
            .getByType(ForgeEnvExtension::class.java)
            .setJsCoremodRemap(
                project.provider { evaluations++; false })
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        assertEquals(0, evaluations,
            "jsCoremodRemap provider must not resolve during configuration")
        val steps = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet("forge")
            .contributedSteps(
                xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
        assertEquals(1, evaluations)
        assertTrue(steps.none {
            it is xyz.wagyourtail.unimined.transform.FunctionalStepSpec &&
                    it.stepId == "forge:jsCoremodRemap"
        }, "jsCoremodRemap=false must drop the deferred " +
                "forge:jsCoremodRemap contribution")
    }

    /** B6.9 — split-side `data/<side>.lzma` packs checksum against the
     *  *installer* pipeline's renamed jar, so the binpatch clean jar's
     *  `rename` tool is sourced from `install_profile.json` (FART
     *  0.1.22 for 47.3.0), not the mcpconfig descriptor's declared
     *  `rename` tool (1.0.2 — byte-divergent output). Joined envs stay
     *  on the descriptor chain. */
    @Test
    fun `split-side userdev sources the binpatch rename tool from the installer profile`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val coords = "net.minecraftforge:forge:1.20.1-47.3.0"
        serveUserdev(coords)
        serveInstaller(
            coords,
            """{"processors": [
                {"jar": "net.minecraftforge:installertools:1.4.1:fatjar"},
                {"jar": "net.minecraftforge:jarsplitter:1.1.4:fatjar"},
                {"jar": "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all"},
                {"jar": "net.minecraftforge:binarypatcher:1.1.1"}
            ]}""")
        val env = forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        env.side.set(xyz.wagyourtail.unimined.api.spec.Side.SERVER)
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        val resolved = xyz.wagyourtail.unimined.transform.forge.internal
            .installer.contributeLoaderResolution(project, env)
        assertEquals(
            "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all",
            xyz.wagyourtail.unimined.transform.forge.internal.binpatch
                .installerRenameToolOverride(project, env, resolved).orNull)
    }

    /** The override stays absent on joined envs (the `joined.lzma`
     *  pack tracks the descriptor chain) and non-Userdev profiles. */
    @Test
    fun `joined and non-userdev envs get no rename tool override`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        serveUserdev("net.minecraftforge:forge:1.20.1-47.3.0")
        val env = forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        // default side is MERGED — the joined binpatch path
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        val resolved = xyz.wagyourtail.unimined.transform.forge.internal
            .installer.contributeLoaderResolution(project, env)
        assertNull(
            xyz.wagyourtail.unimined.transform.forge.internal.binpatch
                .installerRenameToolOverride(project, env, resolved).orNull,
            "joined envs track the descriptor-declared rename tool")
    }

    private data class Product(
        val id: String,
        val mc: String,
        val loaderVer: String,
        val base: String,
        val prodNs: String
    )

}
