package xyz.wagyourtail.unimined.remapper.jst

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * DSL → step-spec emission for the `jst` verb — the Kotlin extension
 * function and the plugin-registered Groovy `stepVerb` closure both
 * land a configured [JstStepSpec] on the SOURCES surface.
 */
class JstDslVerbTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(GradlePlugin::class.java)
    }

    private fun remap(project: org.gradle.api.Project) =
        project.extensions.getByType(RemapExtension::class.java)

    @Test
    fun `kotlin jst verb emits a configured JstStepSpec`() {
        val ext = remap(project())
        ext.surface(RemapSurface.SOURCES).jst { spec ->
            spec.toolClasspath.from("jst-cli.jar")
            spec.enable.addAll("parchment", "unpick")
            spec.extraArgs.add("--lenient")
        }

        val spec = ext.surface(RemapSurface.SOURCES).steps
            .single() as JstStepSpec
        assertEquals(listOf("parchment", "unpick"), spec.enable.get())
        assertEquals(listOf("--lenient"), spec.extraArgs.get())
        assertEquals(
            listOf("jst-cli.jar"),
            spec.toolClasspath.files.map { it.name }
        )
    }

    @Test
    fun `groovy jst verb dispatches through the registered stepVerb`() {
        val ext = remap(project())
        val surface = ext.surface(RemapSurface.SOURCES)

        // Groovy `sources { jst { enable.add "parchment" } }` reaches
        // methodMissing → the registered verb closure → step(...)
        val block = object : Closure<Any?>(this) {
            @Suppress("unused")
            fun doCall(spec: JstStepSpec) {
                spec.enable.add("parchment")
            }
        }
        surface.methodMissing("jst", arrayOf<Any?>(block))

        val spec = surface.steps.single() as JstStepSpec
        assertEquals(listOf("parchment"), spec.enable.get())
    }

    @Test
    fun `stepVerb creates a fresh spec per invocation`() {
        val ext = remap(project())
        val surface = ext.surface(RemapSurface.SOURCES)
        surface.methodMissing("jst", arrayOf<Any?>())
        surface.methodMissing("jst", arrayOf<Any?>())
        assertEquals(2, surface.steps.size)
    }

}
