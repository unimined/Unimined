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
        ).maybeCreate("paper")
        // server-processor/run-config contributions land in
        // afterDslEval — fire it now that loader() is declared
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
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
            override val prod = false
            override var mainClass: String? = null
            override val javaagents = mutableListOf<java.nio.file.Path>()
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        assertTrue(envSpec.runMutators.isNotEmpty(), "run mutators should be wired")
        envSpec.runMutators.forEach { it.execute(runConfig) }
        assertEquals("org.bukkit.craftbukkit.Main", runConfig.mainClass)
        // paper-plugin.yml can't be read by CPL — the dev jar stages
        // into `plugins/` and loads via the server's own plugin
        // classloader instead, so no javaagent/pluginGroups land
        assertTrue(runConfig.javaagents.isEmpty(),
            "paper env stages into plugins/ — no CPL agent")
        assertTrue(runConfig.jvmArgs.none { it.startsWith("-Dcpl.pluginGroups=") },
            "paper env should not declare CPL plugin groups")
        assertTrue(envSpec.prepareTaskMutators.isNotEmpty(),
            "paper env should stage the dev jar into plugins/")
    }

    @Test
    fun `classpath modDiscovery opts into the CPL classpath loader`() {
        project = project()
        serveCpl()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("spigot") { env ->
            env.version.set("1.20.4")
            env.loader("spigot", "1.20.4-R0.1-SNAPSHOT")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("spigot")
        val envSpec = project.extensions
            .getByType(TransformExtension::class.java).sourceSet("spigot")
        // user opt-in: CPL's classpath scan instead of plugins/ staging
        envSpec.features.binding("modDiscovery", "classpath")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val runConfig = object : RunConfigSpec {
            override val name = "server"
            override val prod = false
            override var mainClass: String? = null
            override val javaagents = mutableListOf<java.nio.file.Path>()
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        envSpec.runMutators.forEach { it.execute(runConfig) }
        assertEquals("org.bukkit.craftbukkit.Main", runConfig.mainClass)
        assertTrue(runConfig.javaagents.isNotEmpty(),
            "classpath opt-in should carry the CPL agent")
        assertTrue(runConfig.jvmArgs.any { it.startsWith("-Dcpl.pluginGroups=") })
        assertTrue(envSpec.prepareTaskMutators.isEmpty(),
            "classpath mode should not stage into plugins/")
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
        ).maybeCreate("fabric")
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
        ).maybeCreate("paperapi")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        val envSpec = project.extensions
            .getByType(TransformExtension::class.java).sourceSet("paperapi")
        assertTrue(envSpec.jarProcessors.none {
            it.javaClass.simpleName == "PaperPatchProcessor"
        }, "apiOnly should skip the dist-patch processor")
    }

    @Test
    fun `paper env resolves server side by convention`() {
        project = project()
        val (ext, envId) = paperEnv()
        val env = ext.environments.getByName(envId)
        assertEquals(xyz.wagyourtail.unimined.api.spec.Side.SERVER,
            env.side.get(),
            "bukkit-family envs are server-only — the raw artifact " +
                    "must be the mojang server jar the dist patch expects")
    }

    @Test
    fun `explicit side beats the server convention`() {
        project = project()
        serveCpl()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("paper") { env ->
            env.version.set("1.20.4")
            env.loader("paper", "496")
            env.side.set(xyz.wagyourtail.unimined.api.spec.Side.CLIENT)
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("paper")
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        assertEquals(xyz.wagyourtail.unimined.api.spec.Side.CLIENT,
            ext.environments.getByName("paper").side.get())
    }

    @Test
    fun `non-bukkit envs keep the merged side default`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.4")
            env.loader("fabric", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("fabric")
        assertEquals(xyz.wagyourtail.unimined.api.spec.Side.MERGED,
            ext.environments.getByName("fabric").side.get())
    }

    @Test
    fun `reobf convention offers spigot namespaces`() {
        project = project()
        val (ext, envId) = paperEnv()
        val env = ext.environments.getByName(envId)
        assertTrue(env.modSourceNamespaces.get().contains("spigotProd"),
            "mod-source namespaces should include spigotProd for reobf")
        assertTrue(env.modSourceNamespaces.get().contains("spigot"),
            "mod-source namespaces should include named spigot")
        assertTrue(env.defaultRemapJar.get(),
            "remapJar convention should stay enabled for reobf")
    }
}
