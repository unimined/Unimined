package xyz.wagyourtail.unimined.remapper.source

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * DSL → step-spec emission for the `sourceRemap` verb — Kotlin
 * extension and plugin-registered Groovy `stepVerb` both land a
 * configured [SourceRemapStepSpec] on the SOURCES surface.
 */
class SourceRemapDslTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(GradlePlugin::class.java)
    }

    private fun remap(project: org.gradle.api.Project) =
        project.extensions.getByType(RemapExtension::class.java)

    @Test
    fun `kotlin sourceRemap verb emits a configured spec`() {
        val ext = remap(project())
        ext.surface(RemapSurface.SOURCES).sourceRemap { spec ->
            spec.toolClasspath.from("source-remap.jar")
            spec.extraClasspath.from("extra.jar")
            spec.extraArgs.add("--a")
        }

        val spec = ext.surface(RemapSurface.SOURCES).steps
            .single() as SourceRemapStepSpec
        assertEquals(
            listOf("source-remap.jar"),
            spec.toolClasspath.files.map { it.name }
        )
        assertEquals(
            listOf("extra.jar"),
            spec.extraClasspath.files.map { it.name }
        )
        assertEquals(listOf("--a"), spec.extraArgs.get())
    }

    @Test
    fun `groovy sourceRemap verb dispatches through stepVerb`() {
        val ext = remap(project())
        val surface = ext.surface(RemapSurface.SOURCES)

        val block = object : Closure<Any?>(this) {
            @Suppress("unused")
            fun doCall(spec: SourceRemapStepSpec) {
                spec.toolClasspath.from("tool.jar")
            }
        }
        surface.methodMissing("sourceRemap", arrayOf<Any?>(block))

        val spec = surface.steps.single() as SourceRemapStepSpec
        assertEquals(
            listOf("tool.jar"),
            spec.toolClasspath.files.map { it.name }
        )
    }

}
