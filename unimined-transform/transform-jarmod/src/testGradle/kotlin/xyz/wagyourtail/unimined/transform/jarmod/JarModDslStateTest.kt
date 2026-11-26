package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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


    private fun evaluatedEnv(loaderName: String?): Pair<org.gradle.api.Project, MinecraftEnvironment> {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.plugins.apply(
            xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.transform.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .sourceSet("test") {
                it.inheritParent.set(false)
                it.version.set("1.20.1")
                it.devNamespace.set("named")
                if (loaderName != null) it.loader(loaderName, "1.0")
            }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        return project to env
    }

    @Test
    fun `jarmod family env retains merge widening and dev fix wiring`() {
        val (project, env) = evaluatedEnv("jarmod")
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        val envSpec = transform.sourceSet(env.environmentId)
        assertTrue(envSpec.jarProcessors.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal
                .JarModMergeProcessor
        })
        assertTrue(envSpec.jarProcessors.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal
                .AccessWideningProcessor
        })
        val steps = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet(env.environmentId)
            .contributedSteps(xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
        assertTrue(steps.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal.modloader
                .ModLoaderDevFixesStepSpec
        })
    }

    @Test
    fun `fabric env does not receive jarmod processors or steps`() {
        val (project, env) = evaluatedEnv("fabric")
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        val envSpec = transform.sourceSet(env.environmentId)
        assertFalse(envSpec.jarProcessors.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal
                .JarModMergeProcessor ||
                    it is xyz.wagyourtail.unimined.transform.jarmod.internal
                        .AccessWideningProcessor
        })
        val steps = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet(env.environmentId)
            .contributedSteps(xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
        assertFalse(steps.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal.modloader
                .ModLoaderDevFixesStepSpec
        })
    }

    @Test
    fun `loaderless env does not receive jarmod processors or dev fixes`() {
        val (project, env) = evaluatedEnv(null)
        val envSpec = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
            .sourceSet(env.environmentId)
        assertFalse(envSpec.jarProcessors.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal
                .JarModMergeProcessor ||
                    it is xyz.wagyourtail.unimined.transform.jarmod.internal
                        .AccessWideningProcessor
        })
        val steps = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
            .sourceSet(env.environmentId)
            .contributedSteps(xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
        assertFalse(steps.any {
            it is xyz.wagyourtail.unimined.transform.jarmod.internal.modloader
                .ModLoaderDevFixesStepSpec
        })
    }


}
