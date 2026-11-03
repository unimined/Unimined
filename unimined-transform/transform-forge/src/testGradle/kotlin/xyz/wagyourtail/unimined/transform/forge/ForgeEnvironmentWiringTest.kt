package xyz.wagyourtail.unimined.transform.forge

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
        JarOutputStream(jarDir.resolve("$a-$v-userdev.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("config.json"))
            out.write("""{"mcp": "de.oceanlabs.mcp:mcp_config:1.20.1@zip"}""".toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("binpatches.pack.lzma"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }
    }

    private lateinit var project: org.gradle.api.Project

    private fun forgeEnv(
        ext: MinecraftExtension,
        product: String,
        mc: String,
        loaderVer: String
    ) = ext.sourceSet(product) { env ->
        env.version.set(mc)
        env.loader(product, loaderVer)
    }.also {
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create(product)
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
        ).create("notforge")
        assertNull(env.artifactBaseName.orNull)
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
        // product -> (mc, loaderVer, expected artifact base, expected prodNs)
        val products = listOf(
            Product("forge", "1.20.1", "47.3.0", "forge-1.20.1-47.3.0", "searge"),
            Product("lexforge", "1.20.1", "47.3.0", "forge-1.20.1-47.3.0", "searge"),
            // 1.x convention: the loader arg is the version tail —
            // "77" -> neoforge:21.1.77 for mc 1.21.1
            Product("neoforge", "1.21.1", "77", "neoforge-21.1.77", "mojmap"),
            Product("cleanroom", "1.12.2", "0.2.0-alpha", "cleanroom-0.2.0-alpha", "searge"),
        )
        for ((i, p) in products.withIndex()) {
            val coords = when (p.id) {
                "neoforge" -> "net.neoforged:neoforge:21.1.${p.loaderVer}"
                "cleanroom" -> "com.cleanroommc:cleanroom:${p.loaderVer}"
                else -> "net.minecraftforge:forge:${p.mc}-${p.loaderVer}"
            }
            serveUserdev(coords)
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

    private data class Product(
        val id: String,
        val mc: String,
        val loaderVer: String,
        val base: String,
        val prodNs: String
    )

}
