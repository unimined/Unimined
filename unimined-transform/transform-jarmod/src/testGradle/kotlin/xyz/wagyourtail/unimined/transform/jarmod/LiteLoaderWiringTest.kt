package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.LiteLoaderWiring
import java.nio.file.Files

/**
 * The liteloader env defaults to CLIENT via convention (the loader is
 * client-only), but an explicit non-client `side =` must still fail —
 * the convention must not swallow the guard. The acceptance path reads
 * versions.json from the unimined global cache — seeded into a
 * throwaway gradle user home so no network is involved.
 */
class LiteLoaderWiringTest {

    @Test
    fun `explicit server side is still rejected`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("lite")
        env.side.set(Side.SERVER)
        val ex = assertThrows(GradleException::class.java) {
            LiteLoaderWiring.wire(project, env)
        }
        assertTrue(
            ex.message!!.contains("only supports CLIENT"),
            "expected the client-only guard, got: ${ex.message}")
    }

    /**
     * A versions.json-known MC version wires the loader as a library
     * dep plus its versions.json `libraries` + `tweakClass`. The cache
     * file is seeded under a temp gradle user home, exercising the
     * offline path — a stale/missing seed would throw instead of
     * silently taking the jarMod fallback.
     */
    @Test
    fun `seeded versions json wires deps and tweakClass offline`() {
        val guh = Files.createTempDirectory("liteloader-guh").toFile()
        val versions = guh.toPath()
            .resolve("caches/unimined/liteloader/versions.json")
        Files.createDirectories(versions.parent)
        Files.writeString(versions, """
            {"versions":{"1.7.10":{"snapshots":{
                "libraries":[{"name":"net.minecraft:launchwrapper:1.12"}],
                "com.mumfrey:liteloader":{"entry":{"version":"1.7.10-SNAPSHOT",
                    "tweakClass":"com.mumfrey.liteloader.launch.LiteLoaderTweaker",
                    "libraries":[{"name":"com.google.guava:guava:17.0"}]}}}}}}
            """.trimIndent())

        val project = ProjectBuilder.builder()
            .withGradleUserHomeDir(guh).build()
        project.pluginManager.apply("java")
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.transform.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        project.gradle.startParameter.isOffline = true
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("lite")
        env.inheritParent.set(false)
        env.version.set("1.7.10")

        LiteLoaderWiring.wire(project, env)

        fun depNames(conf: String) = project.configurations
            .getByName(conf).dependencies.map { "${it.group}:${it.name}:${it.version}" }
        assertTrue(
            "com.mumfrey:liteloader:1.7.10-SNAPSHOT" in
                    depNames(env.modImplementationConfigurationName),
            "liteloader dep missing from modImplementation")
        val libs = depNames(env.minecraftLibrariesConfigurationName)
        assertTrue("net.minecraft:launchwrapper:1.12" in libs,
            "bucket libraries missing: $libs")
        assertTrue("com.google.guava:guava:17.0" in libs,
            "artifact libraries missing: $libs")
        assertTrue(project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("lite").runMutators.isNotEmpty(),
            "tweakClass runMutator not registered")
    }

    /** Offline with no seeded cache must fail loudly, not degrade to
     *  the versions.json-unknown jarMod path. */
    @Test
    fun `offline without cached versions json fails`() {
        val guh = Files.createTempDirectory("liteloader-guh").toFile()
        val project = ProjectBuilder.builder()
            .withGradleUserHomeDir(guh).build()
        project.pluginManager.apply("java")
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        project.gradle.startParameter.isOffline = true
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("lite")
        env.inheritParent.set(false)
        env.version.set("1.7.10")
        val ex = assertThrows(GradleException::class.java) {
            LiteLoaderWiring.wire(project, env)
        }
        assertTrue(ex.message!!.contains("offline"),
            "expected the offline cache miss, got: ${ex.message}")
    }
}
