package xyz.wagyourtail.unimined.remapper.access

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * DSL → step-spec emission for the `applyClassTweaker` verb — Kotlin
 * extension and plugin-registered Groovy `stepVerb` both land a
 * configured [ApplyClassTweakerStepSpec] on the JAR surface.
 */
class ApplyClassTweakerDslTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(GradlePlugin::class.java)
    }

    private fun remap(project: org.gradle.api.Project) =
        project.extensions.getByType(RemapExtension::class.java)

    @Test
    fun `kotlin applyClassTweaker verb emits a configured spec`() {
        val ext = remap(project())
        ext.surface(RemapSurface.JAR).applyClassTweaker { spec ->
            spec.classTweakerFiles.from("own.ct")
            spec.dependencyClassTweakerFiles.from("dep.ct")
            spec.headerlessNamespace.set("named")
            spec.validate.set(true)
            spec.namespaceAliases.put("intermediary", "calamus")
        }

        val spec = ext.surface(RemapSurface.JAR).steps
            .single() as ApplyClassTweakerStepSpec
        // widens Minecraft classes only — the env-artifact marker keeps
        // it off dep/prod pipelines
        assertEquals(true, spec is DevArtifactOnlyStepSpec)
        assertEquals(
            listOf("own.ct"), spec.classTweakerFiles.files.map { it.name })
        assertEquals(
            listOf("dep.ct"),
            spec.dependencyClassTweakerFiles.files.map { it.name })
        assertEquals("named", spec.headerlessNamespace.get())
        assertEquals(true, spec.validate.get())
        assertEquals(
            mapOf("intermediary" to "calamus"),
            spec.namespaceAliases.get()
        )
    }

    @Test
    fun `groovy applyClassTweaker verb dispatches through stepVerb`() {
        val ext = remap(project())
        val surface = ext.surface(RemapSurface.JAR)

        val block = object : Closure<Any?>(this) {
            @Suppress("unused")
            fun doCall(spec: ApplyClassTweakerStepSpec) {
                spec.classTweakerFiles.from("groovy.ct")
            }
        }
        surface.methodMissing("applyClassTweaker", arrayOf<Any?>(block))

        val spec = surface.steps.single() as ApplyClassTweakerStepSpec
        assertEquals(
            listOf("groovy.ct"),
            spec.classTweakerFiles.files.map { it.name }
        )
    }

}
