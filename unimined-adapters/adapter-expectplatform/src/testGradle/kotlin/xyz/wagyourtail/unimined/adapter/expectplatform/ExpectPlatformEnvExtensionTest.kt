package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.expect.ExpectPlatformExtension
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.runs.RunSide
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.io.File

/** `platform()` wires agent jar + sysprops onto runs bound to the
 *  env; opt-outs (`enabled`/`agent`) and the loader-derived remap
 *  table behave per spec. */
class ExpectPlatformEnvExtensionTest {

    private val agentJar = File("/tmp/expect-agent.jar")

    private inner class Fixture(val p: org.gradle.api.Project) {
        val runs: RunsExtension =
            p.objects.newInstance(RunsExtension::class.java)
        val remap: RemapExtension =
            p.objects.newInstance(RemapExtension::class.java)
        val transform: TransformExtension =
            p.objects.newInstance(TransformExtension::class.java)
        val minecraft: MinecraftExtension =
            p.extensions.getByType(MinecraftExtension::class.java)

        init {
            p.extensions.create("expectPlatform",
                ExpectPlatformExtension::class.java, p)
            p.configurations.create("modImplementation")
            p.configurations.create("modRuntimeOnly")
        }

        fun env(name: String = "main"): MinecraftEnvironment =
            minecraft.environments.getByName(name).also { env ->
                ExpectPlatformEnvWiring.wire(
                    p, remap, env, p.provider { agentJar }, runs, transform)
            }

        fun spec(name: String = "main") = env(name).extensions
            .getByType(ExpectPlatformEnvExtension::class.java)
    }

    private fun fixture() = Fixture(
        ProjectBuilder.builder().build().also {
            it.pluginManager.apply("java-base")
            it.pluginManager.apply(
                xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        })

    @Test
    fun `platform wires agent args onto the env's runs`() {
        val f = fixture()
        f.runs.runs.register("client") { it.environment.set("main") }
        f.runs.runs.register("otherClient") { it.environment.set("other") }
        val spec = f.spec()
        spec.platform("fabric", mapOf("a.b.C" to "x.y.Z"))

        val bound = f.runs.runs.getByName("client")
        bound.side.set(RunSide.CLIENT)
        assertTrue(bound.javaagents.files.contains(agentJar))
        assertTrue(bound.jvmArgs.get()
            .contains("-Dexpect.platform=fabric"))
        assertTrue(bound.jvmArgs.get()
            .contains("-Dexpect.side=client"))
        assertTrue(bound.jvmArgs.get()
            .any { it.startsWith("-Dexpect.remap=") })

        val other = f.runs.runs.getByName("otherClient")
        assertTrue(other.javaagents.isEmpty)
        assertTrue(other.jvmArgs.get().isEmpty())
    }

    @Test
    fun `loader-derived remap table fills when remap unset`() {
        val f = fixture()
        val spec = f.spec()
        spec.platform("fabric")
        val remap = spec.effectiveRemap!!.get()
        assertEquals("net/fabricmc/api/Environment", remap[
            "xyz/wagyourtail/unimined/expect/annotation/Environment"])
        assertEquals("net/fabricmc/api/EnvType", remap[
            "xyz/wagyourtail/unimined/expect/annotation/Environment\$EnvType"])
    }

    @Test
    fun `explicit remap merges over the loader table`() {
        val f = fixture()
        val spec = f.spec()
        spec.platform("neoforge", mapOf("custom.A" to "custom.B"))
        val remap = spec.effectiveRemap!!.get()
        assertEquals("custom.B", remap["custom.A"])
        assertEquals("net/neoforged/api/distmarker/OnlyIn", remap[
            "xyz/wagyourtail/unimined/expect/annotation/Environment"])
    }

    @Test
    fun `enabled false unwires runs`() {
        val f = fixture()
        f.runs.runs.register("client") { it.environment.set("main") }
        val spec = f.spec()
        spec.platform("fabric")
        spec.enabled.set(false)
        val bound = f.runs.runs.getByName("client")
        bound.side.set(RunSide.CLIENT)
        assertTrue(bound.javaagents.isEmpty)
        assertTrue(bound.jvmArgs.get().isEmpty())
    }

    @Test
    fun `agent false drops javaagent and sysprops`() {
        val f = fixture()
        f.runs.runs.register("client") { it.environment.set("main") }
        val spec = f.spec()
        spec.platform("fabric")
        spec.agent.set(false)
        val bound = f.runs.runs.getByName("client")
        bound.side.set(RunSide.CLIENT)
        assertTrue(bound.javaagents.isEmpty)
        assertTrue(bound.jvmArgs.get().isEmpty())
    }

}
