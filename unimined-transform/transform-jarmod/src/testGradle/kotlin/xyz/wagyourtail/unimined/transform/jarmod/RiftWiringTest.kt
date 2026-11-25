package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState
import xyz.wagyourtail.unimined.transform.jarmod.internal.RiftWiring

/**
 * Rift wiring: the ModernMixins artifact (mixin 0.8.6 + MixinExtras)
 * rides `minecraftLibraries` ahead of the stock dimdev trio by
 * default — compile order exposes its API — and `modernMixins(false)`
 * on the env's `jarmod` extension opts out. The run mutator installs
 * the LaunchWrapper main class, the rift tweaker, and `--mixin` args
 * for every env mixin config. Dependency assertions only inspect
 * declared notations — no resolution, no network.
 */
class RiftWiringTest {

    private fun wireEnv(optOut: Boolean = false): Pair<org.gradle.api.Project, xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment> {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.transform.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("rift")
        env.inheritParent.set(false)
        env.version.set("1.13.2")
        if (optOut) {
            JarModEnvState.modernMixins(env.environmentId, false)
        }
        RiftWiring.wire(project, env)
        return project to env
    }

    private fun depNames(project: org.gradle.api.Project, conf: String) =
        project.configurations.getByName(conf).dependencies
            .map { "${it.group}:${it.name}:${it.version}" }

    @Test
    fun `modernmixins rides minecraft libraries ahead of the stock trio by default`() {
        val (project, env) = wireEnv()
        val libs = project.configurations
            .getByName(env.minecraftLibrariesConfigurationName).dependencies
            .map { "${it.group}:${it.name}" }
        assertTrue("maven.modrinth:modernmixins" in libs,
            "modernmixins missing: $libs")
        assertTrue("org.dimdev:mixin" in libs,
            "stock mixin must stay (conflict detection keys off it): $libs")
        assertTrue(libs.indexOf("maven.modrinth:modernmixins") <
                libs.indexOf("org.dimdev:mixin"),
            "modernmixins must precede the trio for compile-classpath order: $libs")
    }

    @Test
    fun `modernMixins false opts out of the provider`() {
        val (project, env) = wireEnv(optOut = true)
        val libs = depNames(project, env.minecraftLibrariesConfigurationName)
        assertFalse(libs.any { it.startsWith("maven.modrinth:modernmixins") },
            "opt-out must drop modernmixins: $libs")
        assertTrue(libs.any { it.startsWith("org.dimdev:mixin") },
            "stock trio remains: $libs")
    }

    @Test
    fun `run mutator installs launchwrapper main tweaker and mixin args`() {
        val (project, env) = wireEnv()
        env.mixinConfigs.add("testmod.mixins.json")
        val mutators = project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("rift").runMutators
        assertTrue(mutators.isNotEmpty(), "rift runMutator not registered")

        val spec = object : xyz.wagyourtail.unimined.api.spec.RunConfigSpec {
            override val name = "client"
            override var type = "client"
            override val prod = false
            override var mainClass: String? = null
            override val javaagents = mutableListOf<java.nio.file.Path>()
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        mutators.forEach { it.execute(spec) }

        assertEquals("net.minecraft.launchwrapper.Launch", spec.mainClass)
        val args = spec.programArgs
        val tweakIdx = args.indexOf("--tweakClass")
        assertTrue(tweakIdx >= 0, "tweakClass missing: $args")
        assertEquals("org.dimdev.riftloader.launch.RiftLoaderClientTweaker",
            args[tweakIdx + 1])
        val mixinIdx = args.indexOf("--mixin")
        assertTrue(mixinIdx >= 0, "--mixin args missing: $args")
        assertEquals("testmod.mixins.json", args[mixinIdx + 1])
    }

    @Test
    fun `server runs get the server tweaker`() {
        val (project, env) = wireEnv()
        val mutators = project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("rift").runMutators
        val spec = object : xyz.wagyourtail.unimined.api.spec.RunConfigSpec {
            override val name = "server"
            override var type = "server"
            override val prod = false
            override var mainClass: String? = null
            override val javaagents = mutableListOf<java.nio.file.Path>()
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        mutators.forEach { it.execute(spec) }
        val idx = spec.programArgs.indexOf("--tweakClass")
        assertEquals("org.dimdev.riftloader.launch.RiftLoaderServerTweaker",
            spec.programArgs[idx + 1])
    }
}
