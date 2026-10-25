package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import org.gradle.api.GradleException
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

class RemapExtensionTest {

    private class OtherStepSpec : StepSpec

    abstract class ConfigurableStepSpec : StepSpec {
        abstract val marker: Property<String>
    }

    private fun extension(): RemapExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions.getByType(RemapExtension::class.java)
    }

    @Test
    fun `plugin registers the remap extension`() {
        extension()
    }

    @Test
    fun `single remap step per surface validates`() {
        val ext = extension()
        ext.jar { it.remap("tiny") }
        ext.sources { it.remap("uml") }
        assertDoesNotThrow { ext.validate() }
    }

    @Test
    fun `remapAccess declares a configurable access step`() {
        val ext = extension()
        ext.jar { it.remapAccess { spec -> spec.formats = setOf("aw", "at") } }
        val spec = ext.surface(RemapSurface.JAR).steps.single() as AccessRemapStepSpec
        assertEquals(setOf("aw", "at"), spec.formats)
    }

    @Test
    fun `surface without remap step validates`() {
        val ext = extension()
        ext.decompile { it.step(OtherStepSpec()); it.step(OtherStepSpec()) }
        assertDoesNotThrow { ext.validate() }
    }

    @Test
    fun `two remap steps on one surface fail validation`() {
        val ext = extension()
        ext.jar { it.remap("tiny"); it.remap("uml") }
        val e = assertThrows(GradleException::class.java) { ext.validate() }
        assertTrue(e.message!!.contains("JAR"))
    }

    @Test
    fun `steps preserve declaration order`() {
        val ext = extension()
        val s1 = OtherStepSpec()
        val s2 = OtherStepSpec()
        ext.jar { it.remap("tiny"); it.step(s1); it.step(s2) }
        val steps = ext.surface(RemapSurface.JAR).steps
        assertEquals(3, steps.size)
        assertTrue(steps[0] is RemapStepSpec)
        assertEquals(s1, steps[1])
        assertEquals(s2, steps[2])
    }

    @Test
    fun `Groovy closure can configure a managed step spec`() {
        val project = ProjectBuilder.builder().build()
        val spec = RemapSurfaceSpec(RemapSurface.JAR, project.objects)
        val closure = object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as ConfigurableStepSpec).marker.set("configured-from-groovy")
            }
        }

        val configured = spec.step(ConfigurableStepSpec::class.java, closure)

        assertEquals("configured-from-groovy", configured.marker.get())
        assertEquals(listOf(configured), spec.steps)
    }

    @Test
    fun `Groovy closures configure remap surfaces and remapped views`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        project.configurations.create("mods")
        val ext = project.extensions.getByType(RemapExtension::class.java)

        val surfaceClosure = object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as RemapSurfaceSpec).remap()
            }
        }
        ext.jar(surfaceClosure)

        val viewClosure = object : Closure<Any?>(this) {
            fun doCall() {
                val spec = getDelegate() as RemappedViewSpec
                spec.sourceNamespace.set("official")
                spec.targetNamespace.set("named")
            }
        }
        ext.remappedView("mods", viewClosure)

        assertEquals(1, ext.surface(RemapSurface.JAR).steps.size)
        assertNotNull(project.configurations.findByName("modsRemapped"))
    }

    @Test
    fun `defaulted methods expose Java callable overloads`() {
        assertDoesNotThrow {
            RemapSurfaceSpec::class.java.getMethod("remap")
            RemapSurfaceSpec::class.java.getMethod("step", Class::class.java)
            RemapExtension::class.java.getMethod(
                "remappedView", String::class.java, Closure::class.java
            )
        }
    }

    @Test
    fun `remap pipelines are isolated by environment`() {
        val ext = extension()
        ext.sourceSet("fabric") { environment ->
            environment.jar { it.remap("tiny") }
        }
        ext.sourceSet("forge") { environment ->
            environment.jar { it.remap("uml") }
            environment.sources { it.remap("source") }
        }

        assertEquals(
            listOf("tiny"),
            ext.stepSpecs("fabric", RemapSurface.JAR).get().map { (it as RemapStepSpec).engine }
        )
        assertEquals(
            listOf("uml"),
            ext.stepSpecs("forge", RemapSurface.JAR).get().map { (it as RemapStepSpec).engine }
        )
        assertEquals(
            listOf("source"),
            ext.stepSpecs("forge", RemapSurface.SOURCES).get().map { (it as RemapStepSpec).engine }
        )
        assertTrue(ext.configuredSurfaces().isEmpty())
    }

    @Test
    fun `multi namespace views create buckets and source-specific artifact types`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        val ext = project.extensions.getByType(RemapExtension::class.java)

        val views = ext.remappedViews("mods") { spec ->
            spec.targetNamespace.set("named")
            spec.namespace("official")
            spec.namespace("intermediary")
        }

        val artifactType = ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
        assertEquals(setOf("official", "intermediary"), views.views.keys)
        assertNotNull(project.configurations.findByName("modsOfficial"))
        assertNotNull(project.configurations.findByName("modsIntermediary"))
        assertEquals(
            "unimined-remapped-main-named-from-official",
            views.views.getValue("official").attributes.getAttribute(artifactType)
        )
        assertEquals(
            "unimined-remapped-main-named-from-intermediary",
            views.views.getValue("intermediary").attributes.getAttribute(artifactType)
        )
        assertEquals(
            project.configurations.getByName("modsOfficial"),
            views.views.getValue("official").extendsFrom.single()
        )
    }

    @Test
    fun `remapped views use environment-specific artifact types`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        project.configurations.create("fabricMods")
        project.configurations.create("forgeMods")
        val ext = project.extensions.getByType(RemapExtension::class.java)
        ext.sourceSet("fabric") { it.jar { steps -> steps.remap("tiny") } }
        ext.sourceSet("forge") { it.jar { steps -> steps.remap("uml") } }

        fun createView(environment: String, source: String, name: String) =
            ext.remappedViewFor(environment, source, name) { spec ->
                spec.sourceNamespace.set("official")
                spec.targetNamespace.set("named")
            }

        val fabric = createView("fabric", "fabricMods", "fabricModsNamed")
        val forge = createView("forge", "forgeMods", "forgeModsNamed")
        val artifactType = ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
        assertEquals(
            "unimined-remapped-fabric-named",
            fabric.attributes.getAttribute(artifactType)
        )
        assertEquals(
            "unimined-remapped-forge-named",
            forge.attributes.getAttribute(artifactType)
        )
    }

}
