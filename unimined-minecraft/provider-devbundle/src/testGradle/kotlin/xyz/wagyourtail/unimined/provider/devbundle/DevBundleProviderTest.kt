package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.ProductVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.ProductProviderRegistry
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `devbundle` provider: marker support, bundle parse, artifact
 *  contract — exercised against an in-test fixture zip (no network). */
class DevBundleProviderTest {

    @TempDir
    lateinit var projectDir: File

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

    /** The bundle's `config.json` — data-version-5 (buildData) shape. */
    private val configJson = """
        {
          "minecraftVersion": "1.20.4",
          "apiCoordinates": "io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT",
          "mojangApiCoordinates": "io.papermc.paper:paper-mojangapi:1.20.4-R0.1-SNAPSHOT",
          "buildData": {
            "mojangMappedPaperclipFile": "data/paperclip-mojang.jar",
            "reobfMappingsFile": "data/reobf_mappings.tiny",
            "accessTransformFile": "data/accesstransformer.cfg",
            "vanillaJarIncludes": ["*.class"],
            "compileDependencies": ["org.example:compile-lib:1.0"],
            "runtimeDependencies": ["org.example:runtime-lib:2.0"],
            "libraryRepositories": ["https://repo.example.test/maven/"],
            "pluginRemapArgs": ["--named"]
          },
          "patchDir": "patches"
        }
    """.trimIndent()

    /** A fixture dev-bundle zip: config + data/ artifacts + a fake
     *  paperclip (manifest Main-Class, libraries.list, versions.list,
     *  version.json). */
    private fun bundleZip(): File {
        val zip = File(projectDir, "bundle.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun entry(name: String, content: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
            entry("data-version.txt", "5".toByteArray())
            entry("config.json", configJson.toByteArray())
            entry("data/reobf_mappings.tiny", "tiny\t2\t0\tmojang\tspigot\n".toByteArray())
            entry("data/accesstransformer.cfg", "public net.example.Class\n".toByteArray())
            out.putNextEntry(ZipEntry("patches/"))
            out.closeEntry()
            // the paperclip payload — a jar with manifest + bundler
            // layout markers
            val paperclip = File(projectDir, "paperclip.jar")
            JarOutputStream(
                paperclip.outputStream().buffered(),
                Manifest().apply {
                    mainAttributes.putValue("Manifest-Version", "1.0")
                    mainAttributes.putValue("Main-Class", "io.papermc.paperclip.Paperclip")
                }
            ).use { jar ->
                fun jentry(name: String, content: String) {
                    jar.putNextEntry(JarEntry(name))
                    jar.write(content.toByteArray())
                    jar.closeEntry()
                }
                jentry("version.json", """{"id": "paper-1.20.4-1"}""")
                jentry("META-INF/versions.list",
                    "abc123\tpaper-1.20.4-1\tpaper-1.20.4-1.jar")
                jentry("META-INF/libraries.list",
                    "# comment\ndef456\torg.example:bundled-lib:3.0\tlibraries/bundled-lib.jar\n" +
                            "789abc\torg.example:bundled-lib2:4.0\tlibraries/bundled-lib2.jar")
            }
            entry("data/paperclip-mojang.jar", paperclip.readBytes())
            paperclip.delete()
        }
        return zip
    }

    /** Version-json seam — no manifest/network; the stub reports no
     *  downloads so fetches never fire in tests that don't ask. */
    private val stubVersionJson: (ManifestResolver, String) -> VersionJson =
        { _, mc -> VersionJson(id = mc, type = "release") }

    private fun provider() = DevBundleProvider(stubVersionJson)

    @Test
    fun `plugin applies and ensures paper repo`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir).build()
        project.plugins.apply("xyz.wagyourtail.unimined.provider.devbundle")
        assertNotNull(
            project.repositories.findByName("Paper"),
            "Paper maven repo should be ensured"
        )
    }

    @Test
    fun `supports only marked declared sources`() {
        val p = provider()
        assertTrue(
            p.supports(spec(
                DeclaredSource.LocalFile("bundle.zip"),
                DevBundleProvider.FORMAT
            ))
        )
        assertFalse(p.supports(spec(DeclaredSource.LocalFile("x.jar"))))
        assertFalse(p.supports(spec(null)))
    }

    @Test
    fun `producer claims only format-marked specs`() {
        // the transform-side spec carries DeclaredSourceSpec.format —
        // untagged declared sources negotiate to legacydist's floor
        val producer = DevBundleArtifactProducer()
        assertTrue(producer.supports(spec(
            DeclaredSource.LocalFile("bundle.zip"),
            DevBundleProvider.FORMAT
        )))
        assertFalse(producer.supports(
            spec(DeclaredSource.LocalFile("x.jar"))))
        assertFalse(producer.supports(spec(null)))
    }

    @Test
    fun `registers and selects through the provider registry`() {
        val registry = ProductProviderRegistry()
        // capability selection on the marked spec
        val picked = registry.probe(
            spec(DeclaredSource.LocalFile("bundle.zip"),
                DevBundleProvider.FORMAT)
        )
        assertTrue(picked is DevBundleProvider)
        // explicit override ignores the marker entirely
        val override = registry.select(
            spec(DeclaredSource.LocalFile("x.jar")), "devbundle"
        )
        assertTrue(override is DevBundleProvider)
    }

    @Test
    fun `resolve reports the mojmap identity contract`() {
        bundleZip()
        val artifacts = provider().resolve(
            spec(DeclaredSource.LocalFile(
                File(projectDir, "bundle.zip").absolutePath),
                DevBundleProvider.FORMAT
            ), ctx()
        )
        assertTrue(artifacts.artifacts.containsKey(ArtifactKey.MERGED))
        assertEquals(false, artifacts.obfuscated.get())
        assertEquals(
            NamespaceMode.IDENTITY, artifacts.namespaceMode.get()
        )
        assertEquals(
            listOf("mojmap"),
            artifacts.mappings.get().namespaces.map { it.name }
        )
        assertEquals(
            listOf("paperclip:binpatch"),
            artifacts.completedStages[ArtifactKey.MERGED]!!.get().entries
        )
        // the bundle's declared deps + the paperclip's bundled libs
        val libs = artifacts.libraries.get()
            .map { "${it.group}:${it.name}:${it.version}" }
        assertTrue("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT" in libs)
        assertTrue("org.example:compile-lib:1.0" in libs)
        assertTrue("org.example:runtime-lib:2.0" in libs)
        assertTrue("org.example:bundled-lib:3.0" in libs)
        assertTrue("org.example:bundled-lib2:4.0" in libs)
        // capabilities — bundle mc version, stub javaVersion absent
        assertEquals(
            "1.20.4", artifacts.capabilities.get().minecraftVersion
        )
        assertEquals("devbundle", artifacts.capabilities.get().platform)
        assertTrue(artifacts.obfuscatedAuxArtifacts.get().isEmpty())
        assertNull(artifacts.assets.orNull)
        assertNull(artifacts.launch)
    }

    @Test
    fun `auxiliary artifacts extract bundle entries`() {
        bundleZip()
        val artifacts = provider().resolve(
            spec(DeclaredSource.LocalFile(
                File(projectDir, "bundle.zip").absolutePath),
                DevBundleProvider.FORMAT
            ), ctx()
        )
        val reobf = artifacts.auxiliaryArtifacts["reobf_mappings"]!!
            .get().asFile
        assertTrue(reobf.exists())
        assertTrue(reobf.readText().startsWith("tiny\t2\t0"))
        val at = artifacts.auxiliaryArtifacts["access_transformer"]!!
            .get().asFile
        assertTrue(at.readText().contains("net.example.Class"))
        // absent on the stub version json — empty provider, no throw
        assertNull(artifacts.auxiliaryArtifacts["server_mappings"]!!.orNull)
        assertTrue(
            artifacts.auxiliaryArtifacts["paperclip_jar"]!!
                .get().asFile.exists()
        )
    }

    @Test
    fun `produceDevJar runs paperclip and locates the bundler output`() {
        val zip = bundleZip()
        val bundle = DevBundleProduction.readConfig(zip.toPath())
        assertEquals(5, bundle.dataVersion)
        assertEquals("1.20.4", bundle.minecraftVersion)
        assertEquals("data/paperclip-mojang.jar", bundle.mojangMappedPaperclipFile)
        val work = File(projectDir, "produce").toPath()
        val vanilla = File(projectDir, "vanilla.jar")
            .apply { writeBytes(byteArrayOf(1)) }
        val out = DevBundleProduction.produceDevJar(
            zip.toPath(), bundle, work, vanilla.toPath(),
            javaExecutable = null,
            runJava = { inv ->
                // simulate the paperclip's patchonly output: the file
                // the versions.list row points at under versions/
                Files.createDirectories(inv.workingDir.resolve("versions"))
                Files.write(
                    inv.workingDir.resolve("versions/paper-1.20.4-1.jar"),
                    byteArrayOf(9, 9, 9)
                )
            },
            rebuild = false
        )
        assertEquals(
            byteArrayOf(9, 9, 9).toList(),
            Files.readAllBytes(out).toList()
        )
    }

    @Test
    fun `readConfig rejects non-bundle and unsupported zips`() {
        val plain = File(projectDir, "plain.zip")
        ZipOutputStream(plain.outputStream()).use {
            it.putNextEntry(ZipEntry("config.json"))
            it.write("{}".toByteArray())
            it.closeEntry()
        }
        assertThrows(IllegalStateException::class.java) {
            DevBundleProduction.readConfig(plain.toPath())
        }
        val old = File(projectDir, "old.zip")
        ZipOutputStream(old.outputStream()).use {
            it.putNextEntry(ZipEntry("data-version.txt"))
            it.write("99".toByteArray())
            it.closeEntry()
            it.putNextEntry(ZipEntry("config.json"))
            it.write("{}".toByteArray())
            it.closeEntry()
        }
        assertThrows(IllegalStateException::class.java) {
            DevBundleProduction.readConfig(old.toPath())
        }
    }

}
