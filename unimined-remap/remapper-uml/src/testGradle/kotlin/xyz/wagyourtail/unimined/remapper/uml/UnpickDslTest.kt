package xyz.wagyourtail.unimined.remapper.uml

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * DSL → step-spec emission for the `unpick` verb — Kotlin vararg form
 * and Groovy `stepVerb` positional args both land their definition
 * files on [UnpickSourceStepSpec.defs].
 */
class UnpickDslTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(GradlePlugin::class.java)
    }

    private fun remap(project: org.gradle.api.Project) =
        project.extensions.getByType(RemapExtension::class.java)

    @Test
    fun `kotlin unpick varargs land on defs`() {
        val ext = remap(project())
        ext.surface(RemapSurface.SOURCES).unpick("a.unpick", "b.unpick")

        val spec = ext.surface(RemapSurface.SOURCES).steps
            .single() as UnpickSourceStepSpec
        assertEquals(
            listOf("a.unpick", "b.unpick"),
            spec.defs.files.map { it.name }
        )
    }

    @Test
    fun `groovy unpick positional args land on defs plus block config`() {
        val ext = remap(project())
        val surface = ext.surface(RemapSurface.SOURCES)

        // `unpick("extra.unpick") { defs.from "block.unpick" }` —
        // positional args feed extraArgs → defs.from, the trailing
        // closure configures the fresh spec
        val block = object : Closure<Any?>(this) {
            @Suppress("unused")
            fun doCall(spec: UnpickSourceStepSpec) {
                spec.defs.from("block.unpick")
            }
        }
        surface.methodMissing(
            "unpick", arrayOf<Any?>("extra.unpick", block))

        val spec = surface.steps.single() as UnpickSourceStepSpec
        assertEquals(
            listOf("block.unpick", "extra.unpick"),
            spec.defs.files.map { it.name }.sorted()
        )
    }

}
