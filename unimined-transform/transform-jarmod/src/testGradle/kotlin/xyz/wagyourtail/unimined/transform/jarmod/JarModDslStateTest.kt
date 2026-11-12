package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState

/**
 * DSL knobs -> [JarModEnvState] wiring. Env DSL blocks can evaluate
 * more than once per build (Kotlin DSL + task-graph realization), so
 * registration must dedupe — otherwise `-Djma.*` lists and
 * `JarModAgent-*` manifest attrs repeat entries.
 */
class JarModDslStateTest {

    private fun env(name: String): MinecraftEnvironment {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate(name)
    }

    @Test
    fun `transformer registration dedupes repeated evaluation`() {
        val e = env("dedupe")
        e.jarmodTransformer("a.transform")
        e.jarmodTransformer("a.transform")
        e.jarmodTransforms(listOf("a.transform", "b.transform"))
        assertEquals(
            listOf("a.transform", "b.transform"),
            JarModEnvState.transforms("dedupe"))
    }

    @Test
    fun `refmap initializer and fail strategy reach env state`() {
        val e = env("runtime")
        e.jarmodRefmap("mod.refmap.json")
        e.jarmodRefmap("mod.refmap.json")
        e.jarmodInitializer("com.example.Init")
        e.jarmodFailStrategy("CONTINUE")
        e.jarmodDebug()
        assertEquals(listOf("mod.refmap.json"),
            JarModEnvState.refmaps("runtime"))
        assertEquals(listOf("com.example.Init"),
            JarModEnvState.initializers("runtime"))
        assertEquals("CONTINUE", JarModEnvState.failStrategy("runtime"))
        assertEquals(true, JarModEnvState.debug("runtime"))
    }

    @Test
    fun `compiletime transforms flag reaches env state`() {
        val e = env("ct")
        assertEquals(false, JarModEnvState.compiletimeTransforms("ct"))
        e.jarmodCompiletimeTransforms()
        assertEquals(true, JarModEnvState.compiletimeTransforms("ct"))
        e.jarmodCompiletimeTransforms(disable = false)
        assertEquals(false, JarModEnvState.compiletimeTransforms("ct"))
    }

    @Test
    fun `state is per-env`() {
        env("one").jarmodTransformer("one.transform")
        env("two").jarmodTransformer("two.transform")
        assertEquals(listOf("one.transform"),
            JarModEnvState.transforms("one"))
        assertEquals(listOf("two.transform"),
            JarModEnvState.transforms("two"))
    }

}
