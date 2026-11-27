package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.jsonObject
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Files
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
    private fun serveUserdev(coords: String, configJson: String? = null) {
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
            out.write((configJson ?: """{"mcp": "$mcp"}""").toByteArray())
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

    /** Serve a probe-valid FG2 dev package (`dev.json` +
     *  `devbinpatches.pack.lzma`) as the loader's `:userdev` classifier. */
    private fun serveDevPackage(coords: String, devJson: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        JarOutputStream(jarDir.resolve("$a-$v-userdev.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("dev.json"))
            out.write(devJson.toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("devbinpatches.pack.lzma"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        pinLocalRepo(g)
    }

    /** Serve a probe-irrelevant `:universal` classifier — the artifact
     *  only needs to exist for classpath-selection assertions. */
    private fun serveUniversal(coords: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        JarOutputStream(
            jarDir.resolve("$a-$v-universal.jar").outputStream()
        ).use { out ->
            out.putNextEntry(JarEntry("META-INF/neoforge.mods.toml"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
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
    fun `loaderless and non-forge envs do not register forge source tasks`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.sourceSet("forge") { e ->
            e.version.set("1.20.1")
            e.loader("fabric", "1.0")
        }

        // A loaderless main must not claim `genForgeSources`: that is
        // also the canonical decompile task name for an env literally
        // named `forge`. Likewise a non-forge env must not receive the
        // loader-specific chain merely because the forge plugin is
        // present in the project.
        assertNull(project.tasks.findByName("genForgeSources"))
        assertNull(project.tasks.findByName("genForgeForgeSources"))
    }

    @Test
    fun `non-forge feather-shaped mod view resolves through transform params`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // Forge wiring also replays over the implicit main env at
        // evaluation; keep its product resolution local and well-formed.
        ext.main.version.set("1.12.2")
        ext.main.provider.set("fake")

        // official -> intermediary -> named is the feather-family chain
        // shape; the mod view's production namespace is intermediary.
        val mappingFile = dir.resolve("feather.tiny")
        Files.writeString(
            mappingFile,
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n" +
                "c\ta/A\tb/B\tc/C\n"
        )
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("legacyfabric")
        val env = ext.sourceSet("legacyfabric") { e ->
            e.inheritParent.set(false)
            e.version.set("1.12.2")
            e.provider.set("fake")
            e.loader("feathertest", "1.0")
            e.modSourceNamespaces.set(listOf("intermediary"))
            e.mappings { it.file(mappingFile) }
        }

        // A plain jar carries no mod marker, so after parameter isolation
        // the transform passes it through without needing a remap engine.
        // The regression this covers happens earlier: Forge's cross-env
        // access-model provider used to query its absent loader-resolution
        // provider while RemapDependencyTransform isolated parameters.
        val modDir = dir.resolve("repo/com/example/plain-mod/1.0")
        Files.createDirectories(modDir)
        JarOutputStream(Files.newOutputStream(modDir.resolve("plain-mod-1.0.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("data.txt"))
            jar.write("x".toByteArray())
            jar.closeEntry()
        }
        Files.writeString(
            modDir.resolve("plain-mod-1.0.pom"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" +
                "<modelVersion>4.0.0</modelVersion>" +
                "<groupId>com.example</groupId><artifactId>plain-mod</artifactId>" +
                "<version>1.0</version></project>"
        )
        pinLocalRepo("com.example")
        project.dependencies.add(env.modImplementationConfigurationName, "com.example:plain-mod:1.0")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val resolved = project.configurations
            .getByName("legacyfabricModImplementationRemapped")
            .resolve().map { it.name }
        assertEquals(listOf("plain-mod-1.0-named.jar"), resolved)
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

    /** `include` on a forge-family env nests as jarjar with no
     *  `features { nestedJars }` binding — the loader supplies it. */
    @Test
    fun `neoforge env resolves the jarjar format without a binding`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.21.1")
        ext.main.provider.set("fake")
        serveUserdev("net.neoforged:neoforge:21.1.77")
        forgeEnv(ext, "neoforge", "1.21.1", "77")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val remapJar = project.tasks.getByName("remapNeoforgeJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertEquals("jarjar", remapJar.nestedJarsFormat.get())
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

    /** #227 — FML 12/NeoForge 26.x discovers NeoForge from the
     *  universal jar's own manifest, so MDG keeps that jar as a
     *  separate classpath root instead of shading it into Minecraft.
     *  The loader classpath must therefore carry `:universal`, not the
     *  metadata-only `:userdev` probe artifact. */
    @Test
    fun `post-1_21_11 neoforge loader classpath uses the separate universal artifact`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("26.3")
        ext.main.provider.set("fake")
        val coords = "net.neoforged:neoforge:26.3.0.115"
        serveUserdev(
            coords,
            """{"mcp":"net.neoforged:neoform:26.3-1@zip",""" +
                    """"universal":"$coords:universal",""" +
                    """"features":{"noLegacyClasspath":true,""" +
                    """"combinedBinaryPatches":true}}"""
        )
        serveUniversal(coords)
        forgeEnv(ext, "neoforge", "26.3", "115")

        val files = project.configurations
            .getByName("forgeLoaderNeoforge").files
        assertTrue(
            files.any { it.name == "neoforge-26.3.0.115-universal.jar" },
            "the universal jar must be a separate compile/runtime root: $files")
        assertFalse(
            files.any { it.name == "neoforge-26.3.0.115-userdev.jar" },
            "the metadata-only userdev probe artifact is not loader API: $files")
    }

    /** #224 end-to-end wiring shape — a split-side env on a
     *  combined-patch userdev resolves the userdev artifact itself
     *  (`patches.lzma`); no `:installer` artifact is served here, so
     *  the legacy installer lookup would fail resolution outright. */
    @Test
    fun `split-side combined userdev binpatch step consumes the userdev pack`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("26.3")
        ext.main.provider.set("fake")
        val coords = "net.neoforged:neoforge:26.3.0.115"
        serveUserdev(
            coords,
            """{"mcp":"net.neoforged:neoform:26.3-1@zip",""" +
                    """"binpatches":"patches.lzma","features":""" +
                    """{"combinedBinaryPatches":true}}"""
        )
        val env = forgeEnv(ext, "neoforge", "26.3", "115")
        env.side.set(xyz.wagyourtail.unimined.api.spec.Side.SERVER)
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val spec = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet("neoforge")
            .contributedPreSteps(
                xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
                xyz.wagyourtail.unimined.remap.spi.StepScope.ENVIRONMENT_ARTIFACT)
            .filterIsInstance<xyz.wagyourtail.unimined.transform.forge
                .internal.userdev.UserdevBinpatchStepSpec>()
            .single()
        assertEquals("patches.lzma", spec.binpatchesKey.get())
        assertEquals("neoforge-26.3.0.115-userdev.jar",
            spec.installerJar.get().asFile.name)
    }

    /** #227 — the 1.21.9–1.21.11 middle era still shades NeoForge,
     *  but must retain the universal manifest FML scans for. The shade
     *  is a pre-remap `jar-processors` contribution, so assert the
     *  serialized processor spec wiring rather than only the version
     *  gate helper. */
    @Test
    fun `neoforge 1_21_9 universal shade keeps the system-mod manifest`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.21.9")
        ext.main.provider.set("fake")
        val coords = "net.neoforged:neoforge:21.9.16-beta"
        serveUserdev(
            coords,
            """{"mcp":"net.neoforged:neoform:1.21.9-20250930.151910@zip",""" +
                    """"universal":"$coords:universal","binpatches":"joined.lzma"}"""
        )
        serveUniversal(coords)
        forgeEnv(ext, "neoforge", "1.21.9", "21.9.16-beta")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val payloads = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet("neoforge")
            .contributedPreSteps(
                xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
                xyz.wagyourtail.unimined.remap.spi.StepScope.ENVIRONMENT_ARTIFACT)
            .filterIsInstance<xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec>()
            .flatMap { it.processorSpecs.get() }
        val shade = payloads.singleOrNull {
            it.startsWith("userdev-universal-shade ")
        }
        assertNotNull(shade,
            "1.21.9 must keep the in-jar universal linkage: $payloads")
        val spec = xyz.wagyourtail.unimined.transform.forge.processor
            .UserdevUniversalShadeProcessor()
            .deserializeSpec(shade!!.substringAfter(' ')) as
                xyz.wagyourtail.unimined.transform.forge.processor
                    .UserdevUniversalShadeProcessor.Spec
        assertTrue(spec.keepManifest,
            "FML 10/11 discovers NeoForge by the shaded universal manifest")
    }

    /** #227 — post-1.21.11 the universal must not be a jar processor
     *  at all: it is the separate loader-classpath artifact asserted
     *  by the loader-classpath test. */
    @Test
    fun `post-1_21_11 neoforge does not contribute universal shading`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("26.3")
        ext.main.provider.set("fake")
        val coords = "net.neoforged:neoforge:26.3.0.115"
        serveUserdev(
            coords,
            """{"mcp":"net.neoforged:neoform:26.3-1@zip",""" +
                    """"universal":"$coords:universal","binpatches":"patches.lzma",""" +
                    """"features":{"combinedBinaryPatches":true}}"""
        )
        serveUniversal(coords)
        forgeEnv(ext, "neoforge", "26.3", "115")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val payloads = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet("neoforge")
            .contributedPreSteps(
                xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR,
                xyz.wagyourtail.unimined.remap.spi.StepScope.ENVIRONMENT_ARTIFACT)
            .filterIsInstance<xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec>()
            .flatMap { it.processorSpecs.get() }
        assertFalse(payloads.any { it.startsWith("userdev-universal-shade ") },
            "separate-universal eras must not shade the loader into Minecraft: $payloads")
    }

    /** #228 — family claims carry production-relevant feature bindings.
     *  Environment binding must evaluate the claim layer itself; the legacy
     *  transformer pipeline is not the only consumer. `nestedJars` is
     *  deliberately absent here: its format is version-gated loader
     *  metadata, not a family-wide claim binding. */
    @Test
    fun `forge environment binding materializes claim feature bindings`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.21.1")
        ext.main.provider.set("fake")
        serveUserdev("net.neoforged:neoforge:21.1.77")
        forgeEnv(ext, "neoforge", "1.21.1", "77")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

        val bindings = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java
        ).sourceSet("neoforge").features.bindings
        assertFalse(bindings.containsKey("nestedJars"),
            "nested-jar format must come from version-gated loader metadata")
        assertEquals("modClasses", bindings["modDiscovery"])
    }

    /** #224 — `features.combinedBinaryPatches` means the userdev jar's
     *  own joined pack is authoritative even on split-side envs; the
     *  installer's `data/<side>.lzma` belongs to the production
     *  installer chain, not the dev patch pipeline. */
    @Test
    fun `combined binary patches select the joined userdev pack on split sides`() {
        val config = xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"binpatches":"patches.lzma","features":""" +
                        """{"combinedBinaryPatches":true}}"""
            ).jsonObject
        )
        val profile = xyz.wagyourtail.unimined.transform.forge.internal
            .ForgeProfile.Userdev(config)
        for (side in listOf(
            xyz.wagyourtail.unimined.api.spec.Side.CLIENT,
            xyz.wagyourtail.unimined.api.spec.Side.SERVER,
            xyz.wagyourtail.unimined.api.spec.Side.MERGED
        )) {
            assertEquals(
                "patches.lzma",
                xyz.wagyourtail.unimined.transform.forge.internal.binpatch
                    .userdevBinpatchesKey(profile, side),
                "combined patches must not become data/${side.name.lowercase()}.lzma")
        }
    }

    /** NeoForge's combined patch bundles are always read against the
     *  base type the userdev binpatcher declares (`JOINED` on 26.x),
     *  even when the consuming environment is client/server. */
    @Test
    fun `patch bundle base type follows the userdev binpatcher args`() {
        val config = xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"binpatcher":{"version":"net.neoforged.installertools:binarypatcher:4.0.17:fatjar","args":["--patch","--base","{clean}","--base-type","JOINED","--output","{output}","--patches","{patch}"]}}"""
            ).jsonObject
        )
        val profile = xyz.wagyourtail.unimined.transform.forge.internal
            .ForgeProfile.Userdev(config)
        assertEquals(
            xyz.wagyourtail.unimined.transform.forge.internal.binpatch
                .BinpatchesPackPatcher.PatchBase.JOINED,
            xyz.wagyourtail.unimined.transform.forge.internal.binpatch
                .userdevBinpatchBaseType(profile)
        )
    }

    /** #227 era gate — mirrors MDG: manifest-carrying in-jar NeoForge
     *  for 1.21.9–1.21.11, a separate universal root afterwards. */
    @Test
    fun `userdev universal linkage follows the FML discovery era`() {
        fun linkage(mc: String) =
            xyz.wagyourtail.unimined.transform.forge.internal
                .userdevUniversalLinkage(mc)
        assertEquals(
            xyz.wagyourtail.unimined.transform.forge.internal
                .UserdevUniversalLinkage.SHADED,
            linkage("1.21.8"))
        assertEquals(
            xyz.wagyourtail.unimined.transform.forge.internal
                .UserdevUniversalLinkage.SHADED_WITH_MANIFEST,
            linkage("1.21.9"))
        assertEquals(
            xyz.wagyourtail.unimined.transform.forge.internal
                .UserdevUniversalLinkage.SHADED_WITH_MANIFEST,
            linkage("1.21.11"))
        assertEquals(
            xyz.wagyourtail.unimined.transform.forge.internal
                .UserdevUniversalLinkage.SEPARATE,
            linkage("26.3"))
    }

    /** Legacy FG2 metadata carries structured Mojang library entries.
     *  They must be evaluated structurally and then pass through the same
     *  rewrite/filter channels as provider-declared version libraries. */
    @Test
    fun `FG2 dev libraries apply platform rules natives shape and env filters`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.7.10")
        ext.main.provider.set("fake")
        val coords = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10"
        val os = xyz.wagyourtail.unimined.minecraft.metadata.PlatformInfo
            .current().osName
        val otherOs = if (os == "linux") "windows" else "linux"
        serveDevPackage(coords, """
            {"libraries":[
              {"name":"com.example:current-platform:1.0","rules":[
                {"action":"allow","os":{"name":"$os"}}
              ]},
              {"name":"com.example:other-platform:1.0","rules":[
                {"action":"allow","os":{"name":"$otherOs"}}
              ]},
              {"name":"tv.twitch:twitch-platform:5.16",
               "natives":{"$os":"natives-$os"},
               "downloads":{"natives-$os":{"url":"https://example.invalid/natives.jar"}}},
              {"name":"com.example:dropped:1.0"},
              {"name":"com.example:rewrite:1.0"}
            ]}
        """.trimIndent())
        val env = forgeEnv(ext, "lexforge", "1.7.10", "10.13.4.1614-1.7.10")
        env.libraryFilter { it.name != "dropped" }
        env.libraryCoordFilter {
            if (it == "com.example:rewrite:1.0") "com.example:rewritten:2.0" else it
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val deps = project.configurations
            .getByName("forgeUserdevLibrariesLexforge")
            .incoming.dependencies
        val coordsInConf = deps.map { "${it.group}:${it.name}:${it.version}" }

        assertTrue("com.example:current-platform:1.0" in coordsInConf,
            "current-platform library missing: $coordsInConf")
        assertTrue("com.example:rewritten:2.0" in coordsInConf,
            "libraryCoordFilter rewrite missing: $coordsInConf")
        for (absent in listOf(
            "com.example:other-platform:1.0",
            "tv.twitch:twitch-platform:5.16",
            "com.example:dropped:1.0",
            "com.example:rewrite:1.0"
        )) {
            assertFalse(absent in coordsInConf,
                "$absent must not reach the userdev library configuration: $coordsInConf")
        }
    }

    /** Exact-coordinate repairs are data-driven userdev metadata fixes,
     *  not version preferences scattered through the transformer. */
    @Test
    fun `userdev library coordinates pass through exact upstream repairs`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val coords = "net.minecraftforge:forge:1.20.1-47.3.0"
        serveUserdev(coords, """
            {"mcp":"de.oceanlabs.mcp:mcp_config:1.20.1@zip",
             "libraries":[
               "cpw.mods:securejarhandler:3.0.8@jar",
               "com.google.code.gson:gson:2.8.0",
               "net.minecraft:launchwrapper:1.12"
             ]}
        """.trimIndent())
        forgeEnv(ext, "forge", "1.20.1", "47.3.0")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        assertEquals(
            "cpw.mods:securejarhandler:3.0.9@jar",
            xyz.wagyourtail.unimined.transform.forge.internal.userdev
                .UserdevExtract.repairLibraryCoord(
                    "cpw.mods:securejarhandler:3.0.8@jar")
        )
        val coordsInConf = project.configurations
            .getByName("forgeUserdevLibrariesForge")
            .incoming.dependencies
            .map { "${it.group}:${it.name}:${it.version}" }
        assertTrue("cpw.mods:securejarhandler:3.0.9" in coordsInConf,
            "securejarhandler repair missing: $coordsInConf")
        assertTrue("com.google.code.gson:gson:2.8.9" in coordsInConf,
            "gson repair missing: $coordsInConf")
        assertTrue("net.minecraft:launchwrapper:1.12" in coordsInConf,
            "unrepaired coordinate must pass through: $coordsInConf")
    }

    private data class Product(
        val id: String,
        val mc: String,
        val loaderVer: String,
        val base: String,
        val prodNs: String
    )

}
