package xyz.wagyourtail.unimined.transform.bukkit

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Verifies the bukkit transform's descriptor-driven wiring through the
 * real env-binding path: maven registration, the `distPatch` jar
 * processor, the ClasspathPluginLoader configuration, and the server
 * run-config mutator (craftbukkit main class + cpl plugin groups).
 */
class BukkitEnvironmentWiringTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var project: org.gradle.api.Project

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { p ->
            p.plugins.apply("java")
            p.plugins.apply("xyz.wagyourtail.unimined.remap")
            p.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            p.plugins.apply("xyz.wagyourtail.unimined.transform")
            p.plugins.apply("xyz.wagyourtail.unimined.transform.bukkit")
        }

    /** Serve the CPL agent jar from a local maven repo so the
     *  `classpathPluginLoader<env>` configuration resolves without
     *  touching the network. */
    private fun serveCpl() {
        val jarDir = dir.resolve(
            "repo/xyz/wagyourtail/unimined/classpath-plugin-loader/0.1.0-SNAPSHOT")
        jarDir.toFile().mkdirs()
        JarOutputStream(
            jarDir.resolve("classpath-plugin-loader-0.1.0-SNAPSHOT-all.jar")
                .outputStream()
        ).use { it.putNextEntry(JarEntry("dummy")); it.closeEntry() }
        project.repositories.maven {
            it.url = dir.resolve("repo").toFile().toURI()
            it.metadataSources { s -> s.artifact() }
        }
    }

    private fun paperEnv(): Pair<MinecraftExtension, String> {
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("paper") { env ->
            env.version.set("1.20.4")
            env.loader("paper", "496")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("paper")
        return ext to "paper"
    }

    @Test
    fun `paper env gets distPatch processor and server run config`() {
        project = project()
        serveCpl()
        val (ext, envId) = paperEnv()
        val envSpec = project.extensions
            .getByType(TransformExtension::class.java).sourceSet(envId)

        assertTrue(envSpec.jarProcessors.any {
            it.javaClass.simpleName == "PaperPatchProcessor"
        }, "distPatch env should carry the paper patch processor")

        val cplConfig = project.configurations
            .findByName("classpathPluginLoader${envId.replaceFirstChar { it.uppercaseChar() }}")
        assertNotNull(cplConfig, "classpathPluginLoader config should exist")

        val runConfig = object : RunConfigSpec {
            override val name = "server"
            override var mainClass: String? = null
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        assertTrue(envSpec.runMutators.isNotEmpty(), "run mutators should be wired")
        envSpec.runMutators.forEach { it.execute(runConfig) }
        assertEquals("org.bukkit.craftbukkit.Main", runConfig.mainClass)
        assertTrue(runConfig.jvmArgs.any { it.startsWith("-javaagent:") })
        assertTrue(runConfig.jvmArgs.any { it.startsWith("-Dcpl.pluginGroups=") })
    }

    @Test
    fun `non-bukkit envs get no bukkit conventions`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.4")
            env.loader("fabric", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")
        val envSpec = project.extensions
            .getByType(TransformExtension::class.java).sourceSet("fabric")
        assertTrue(envSpec.jarProcessors.none {
            it.javaClass.simpleName == "PaperPatchProcessor"
        })
    }

    @Test
    fun `apiOnly env compiles api artifact and skips server processing`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("paperapi") { env ->
            env.version.set("1.20.4")
            env.loader("paper", "496")
            env.apiOnly.set(true)
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("paperapi")
        val envSpec = project.extensions
            .getByType(TransformExtension::class.java).sourceSet("paperapi")
        assertTrue(envSpec.jarProcessors.none {
            it.javaClass.simpleName == "PaperPatchProcessor"
        }, "apiOnly should skip the dist-patch processor")
    }
}
