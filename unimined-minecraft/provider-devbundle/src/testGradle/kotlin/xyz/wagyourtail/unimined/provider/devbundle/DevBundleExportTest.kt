package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.ProductVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.provider.devbundle.task.ExportDevBundleTask
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.name

/** Dev-bundle *export*: the producer half — spec conventions, task
 *  registration, bundle layout, and the end-to-end round trip back
 *  through [DevBundleProvider] (including a real JVM run of the
 *  generated paperclip stand-in). No network. */
class DevBundleExportTest {

    @TempDir
    lateinit var projectDir: File

    private fun project(
        configure: Project.() -> Unit = {}
    ): Project = ProjectBuilder.builder()
        .withProjectDir(projectDir).build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.provider.devbundle")
            project.configure()
        }

    private fun env(
        project: Project,
        version: String = "1.20.4",
        side: Side = Side.MERGED
    ): MinecraftEnvironment =
        project.extensions.getByType(MinecraftExtension::class.java)
            .main.apply {
                this.version.set(version)
                this.side.set(side)
            }

    private fun spec(env: MinecraftEnvironment): DevBundleExportSpec =
        env.extensions.getByType(DevBundleExportSpec::class.java)

    private fun fixtureJar(name: String, bytes: ByteArray = byteArrayOf(7, 7, 7)): File =
        File(projectDir, name).apply { writeBytes(bytes) }

    private fun exportSpec(serverJar: File): DevBundleExport.Spec =
        DevBundleExport.Spec(
            minecraftVersion = "1.20.4",
            bundleId = "test-fork-1.20.4",
            serverJar = serverJar.toPath(),
            libraries = listOf(
                DevBundleExport.Library(
                    "com.example:lib:1.0",
                    fixtureJar("lib-1.0.jar", byteArrayOf(3)).toPath()
                ),
                DevBundleExport.Library("com.example:coords-only:2.0")
            ),
            libraryRepositories = listOf("https://repo.example.test/maven/"),
            apiCoordinates = "com.example:api:1.0",
            reobfMappingsFile =
                fixtureJar("reobf.tiny", "tiny\t2\t0\tmojang\tspigot\n".toByteArray()).toPath(),
            sourcePatches = File(projectDir, "srcPatches").apply {
                mkdirs()
                resolve("net/example/Foo.java.patch").apply {
                    parentFile.mkdirs()
                    writeText("--- a\n+++ b\n")
                }
            }.toPath(),
            mainClass = "net.minecraft.server.Main"
        )

    // ---------- spec / wiring ----------

    @Test
    fun `devBundleExport extension exists on every env`() {
        val environment = env(project())
        assertNotNull(spec(environment))
    }

    @Test
    fun `export task is registered per env with spec defaults`() {
        val project = project()
        env(project)
        val task = project.tasks.named("exportDevBundle",
            ExportDevBundleTask::class.java).get()
        assertEquals("unimined", task.group)
        assertEquals("1.20.4", task.minecraftVersion.get())
        assertEquals("unimined-main-1.20.4", task.bundleId.get())
        assertEquals("net.minecraft.server.Main", task.mainClass.get())
        assertTrue(task.outputZip.get().asFile.path
            .endsWith("devBundle/main-dev-bundle.zip"))
        assertEquals("MERGED", task.envSide.get())
        // pre-convention `exportMainDevBundle` stays as a deprecated
        // alias pointing at the canonical task
        val alias = project.tasks.getByName("exportMainDevBundle")
        assertTrue(alias.dependsOn.any {
            it.toString().contains("exportDevBundle")
        }, "alias should depend on the canonical task")
    }

    @Test
    fun `second env gets its own spec and task`() {
        val project = project()
        env(project)
        project.extensions.getByType(MinecraftExtension::class.java)
            .sourceSet("fork").apply { version.set("1.21") }
        assertNotNull(project.tasks.findByName("exportForkDevBundle"))
        assertNotNull(project.extensions
            .getByType(MinecraftExtension::class.java)
            .sourceSet("fork").extensions
            .findByName("devBundleExport"))
    }

    @Test
    fun `task action exports a consumable bundle`() {
        val project = project()
        env(project)
        val serverJar = fixtureJar("server.jar", byteArrayOf(9, 9, 9))
        val task = project.tasks.named("exportDevBundle",
            ExportDevBundleTask::class.java).get()
        // explicit inputs — the library-conf conventions would resolve
        // the env's provider chain, which needs a real product spec
        task.serverJar.set(serverJar)
        task.libraryCoordinates.set(listOf("com.example:lib:1.0"))
        task.embeddedLibraries.setFrom(emptyList<File>())
        task.export()
        val bundle = DevBundleProduction.readConfig(
            task.outputZip.get().asFile.toPath())
        assertEquals(6, bundle.dataVersion)
        assertEquals("1.20.4", bundle.minecraftVersion)
    }

    // ---------- core export ----------

    @Test
    fun `export writes the paperweight layout`() {
        val out = File(projectDir, "out.zip")
        DevBundleExport.export(exportSpec(fixtureJar("srv.jar")), out.toPath())
        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("data-version.txt"))
            assertNotNull(zip.getEntry("config.json"))
            assertNotNull(zip.getEntry("data/paperclip-mojang.jar"))
            assertNotNull(zip.getEntry("data/mojang-spigot-reobf.tiny"))
            assertNotNull(zip.getEntry("patches/net/example/Foo.java.patch"))
        }
        val bundle = DevBundleProduction.readConfig(out.toPath())
        assertEquals(6, bundle.dataVersion)
        assertEquals("1.20.4", bundle.minecraftVersion)
        assertEquals("data/paperclip-mojang.jar", bundle.mojangMappedPaperclipFile)
        assertEquals("data/mojang-spigot-reobf.tiny", bundle.reobfMappingsFile)
        assertEquals("patches", bundle.patchDir)
        assertEquals(listOf("https://repo.example.test/maven/"),
            bundle.libraryRepositories)
        // api coordinate flows into the library channel
        assertTrue("com.example:api:1.0" in bundle.libraryCoordinates)
    }

    @Test
    fun `paperclip jar carries the bundler layout and embeds the server`() {
        val out = File(projectDir, "out.zip")
        val serverBytes = byteArrayOf(1, 2, 3, 4)
        DevBundleExport.export(exportSpec(fixtureJar("srv.jar", serverBytes)), out.toPath())
        val paperclip = DevBundleProduction.extractEntry(
            out.toPath(), "data/paperclip-mojang.jar",
            File(projectDir, "paperclip.jar").toPath())!!
        JarFile(paperclip.toFile()).use { jar ->
            assertEquals(
                DevBundleExport.BOOTSTRAP_CLASS,
                jar.manifest.mainAttributes.getValue("Main-Class")
            )
            assertEquals(
                "net.minecraft.server.Main",
                jar.getInputStream(jar.getJarEntry("META-INF/main-class"))
                    .use { it.readBytes().decodeToString() }
            )
            assertEquals(
                "test-fork-1.20.4.jar",
                jar.getInputStream(jar.getJarEntry("META-INF/versions.list"))
                    .use { it.readBytes().decodeToString() }
                    .split("\t")[2].trim()
            )
            val libraries = jar.getInputStream(
                jar.getJarEntry("META-INF/libraries.list"))
                .use { it.readBytes().decodeToString() }
            assertTrue("com.example:lib:1.0" in libraries)
            assertTrue("com.example:coords-only:2.0" in libraries)
            // embedded jar + bootstrap class
            assertNotNull(jar.getJarEntry("META-INF/libraries/lib-1.0.jar"))
            assertNotNull(jar.getJarEntry(
                DevBundleExport.BOOTSTRAP_CLASS.replace('.', '/') + ".class"))
            assertEquals(
                serverBytes.toList(),
                jar.getInputStream(jar.getJarEntry(
                    "META-INF/versions/test-fork-1.20.4.jar"))
                    .use { it.readBytes().toList() }
            )
        }
    }

    @Test
    fun `export is deterministic`() {
        val a = File(projectDir, "a.zip")
        val b = File(projectDir, "b.zip")
        DevBundleExport.export(exportSpec(fixtureJar("srv.jar")), a.toPath())
        DevBundleExport.export(exportSpec(fixtureJar("srv.jar")), b.toPath())
        assertEquals(a.readBytes().toList(), b.readBytes().toList())
    }

    @Test
    fun `pairLibraries pairs coords to files and keeps the rest`() {
        val libs = DevBundleExport.pairLibraries(
            listOf("com.example:a:1.0", "com.example:b:2.0"),
            listOf(
                File("a-1.0.jar").toPath(),
                File("unmatched-9.jar").toPath()
            )
        )
        assertEquals("com.example:a:1.0", libs[0].coordinate)
        assertEquals("a-1.0.jar", libs[0].file?.name)
        assertEquals("com.example:b:2.0", libs[1].coordinate)
        assertEquals(null, libs[1].file)
        assertEquals("unmatched-9", libs[2].coordinate)
        assertEquals("unmatched-9.jar", libs[2].file?.name)
    }

    // ---------- round trip through the reader ----------

    private fun ctx(): ProviderContext {
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir).build()
        return object : ProviderContext {
            override val layout = project.layout
            override val objects = project.objects
            override val dependencyFactory = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(DependencyFactory::class.java)
            override val providers = project.providers
            override val exec = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(ExecOperations::class.java)
            override val logger =
                Logging.getLogger(DevBundleProvider::class.java)
            override val javaToolchains = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(
                    org.gradle.jvm.toolchain.JavaToolchainService::class.java
                )
            override val useToolchains = project.providers.provider { true }
            override val offline = false
            override val forceReload = false
            override val refreshDependencies = false
            override val refreshDownloads = false
            override val rebuildArtifacts = false
            override val metadataCacheTtl = null
        }
    }

    private fun spec(source: DeclaredSource?, format: String? = null) =
        object : ProductSpec {
            override val environmentId = "main"
            override val version: ProductVersionSpec =
                RuntimeVersionSpec("1.20.4")
            override val side = Side.MERGED
            override val loader = null
            override val declaredSource =
                source?.let { DeclaredSourceSpec(it, "official", format) }
        }

    /** Stub reports a server download — pre-placed in the downloader
     *  cache so no fetch ever fires. */
    private val stubVersionJson: (ManifestResolver, String) -> VersionJson =
        { _, mc -> VersionJson(
            id = mc, type = "release",
            downloads = mapOf("server" to VersionJson.Download(
                url = "https://unreachable.invalid/server.jar",
                sha1 = null
            ))
        ) }

    @Test
    fun `exported bundle round-trips through the provider`() {
        // 1) produce a bundle
        val serverBytes = byteArrayOf(5, 4, 3, 2, 1)
        val out = File(projectDir, "fork-bundle.zip")
        DevBundleExport.export(
            exportSpec(fixtureJar("srv.jar", serverBytes)),
            out.toPath()
        )

        // 2) pre-place the vanilla server in the downloader cache —
        //    the bootstrap ignores it, but the reader stages it
        val cacheDir = File(projectDir, ".gradle/unimined/native-cache")
        File(cacheDir, "versions/1.20.4/server.jar")
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }

        // 3) consume: patchedJar resolves to the embedded jar bytes —
        //    the bootstrap class REALLY runs (javaexec subprocess)
        val artifacts = DevBundleProvider(stubVersionJson).resolve(
            spec(DeclaredSource.LocalFile(out.absolutePath),
                DevBundleProvider.FORMAT), ctx()
        )
        val patched = artifacts.artifacts[ArtifactKey.MERGED]!!.get().asFile
        assertEquals(serverBytes.toList(), patched.readBytes().toList())

        // 4) the artifact ladder: coords + aux channels flow
        val libs = artifacts.libraries.get()
            .map { "${it.group}:${it.name}:${it.version}" }
        assertTrue("com.example:lib:1.0" in libs)
        assertTrue("com.example:coords-only:2.0" in libs)
        assertTrue("com.example:api:1.0" in libs)
        assertEquals(
            "tiny\t2\t0\tmojang\tspigot\n",
            artifacts.auxiliaryArtifacts["reobf_mappings"]!!
                .get().asFile.readText()
        )
    }

    @Test
    fun `produceDevJar runs the exported bootstrap end-to-end`() {
        // same path, driving the shared production object directly
        val serverBytes = byteArrayOf(8, 8, 8)
        val out = File(projectDir, "fork-bundle.zip")
        DevBundleExport.export(
            exportSpec(fixtureJar("srv.jar", serverBytes)),
            out.toPath()
        )
        val bundle = DevBundleProduction.readConfig(out.toPath())
        val produced = DevBundleProduction.produceDevJar(
            out.toPath(), bundle,
            File(projectDir, "work").apply { mkdirs() }.toPath(),
            File(projectDir, "vanilla.jar").apply { writeBytes(byteArrayOf(1)) }.toPath(),
            javaExecutable = null,
            runJava = DevBundleProduction.processRunner,
            rebuild = false
        )
        assertEquals(serverBytes.toList(),
            Files.readAllBytes(produced).toList())
    }

    @Test
    fun `export refuses a missing server jar`() {
        assertThrows(IllegalArgumentException::class.java) {
            DevBundleExport.export(
                exportSpec(File(projectDir, "nonexistent.jar")),
                File(projectDir, "out.zip").toPath()
            )
        }
    }

}
