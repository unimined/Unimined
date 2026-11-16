package xyz.wagyourtail.unimined.remap

import groovy.lang.DelegatesTo
import groovy.lang.GroovyShell
import groovy.lang.Closure
import groovy.lang.MissingMethodException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Groovy DSL dispatch — `RemapSurfaceSpec` is `ExtensionAware` and
 * plugin-registered verb objects (`RemapExtension.registerDslVerb`)
 * must be reachable from Groovy evaluation, where Kotlin extension
 * functions (e.g. `fun RemapSurfaceSpec.jst(...)`) do not dispatch.
 *
 * `jst {}` in a Groovy `build.gradle` resolves through the decorated
 * object's extensions — a spec created with a plain constructor has
 * no decoration and this would fail, which is exactly the regression
 * this test guards.
 */
class GroovyDslDispatchTest {

    open class MarkerStepSpec : StepSpec {
        var seen = mutableListOf<String>()
    }

    private fun extension(project: org.gradle.api.Project): RemapExtension {
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions.getByType(RemapExtension::class.java)
    }

    @Test
    fun `registered verb dispatches from groovy evaluation`() {
        val project = ProjectBuilder.builder().build()
        val ext = extension(project)
        // what an engine plugin's apply() does
        ext.registerDslVerb("marker") { spec -> spec.stepVerb(MarkerStepSpec::class.java) }

        val spec = ext.surface(RemapSurface.SOURCES)
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        // mirrors `sources { marker { seen.add("hit") } }` in a
        // groovy build.gradle — closure coerces to the Action param
        shell.evaluate("spec.marker { it.seen.add('hit') }")

        assertEquals(1, spec.steps.size)
        val step = spec.steps.single() as MarkerStepSpec
        assertEquals(listOf("hit"), step.seen)
    }

    @Test
    fun `verb registered after spec creation lands on the existing spec`() {
        val project = ProjectBuilder.builder().build()
        val ext = extension(project)
        val spec = ext.surface(RemapSurface.JAR)

        ext.registerDslVerb("marker") { s -> s.stepVerb(MarkerStepSpec::class.java) }

        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        shell.evaluate("spec.marker { it.seen.add('late') }")
        assertTrue(spec.steps.single() is MarkerStepSpec)
    }

    @Test
    fun `kotlin extension-function path still works beside groovy`() {
        val project = ProjectBuilder.builder().build()
        val ext = extension(project)
        ext.registerDslVerb("marker") { s -> s.stepVerb(MarkerStepSpec::class.java) }
        val spec = ext.surface(RemapSurface.SOURCES)
        // the member path both languages share
        val step = spec.step(MarkerStepSpec::class.java) { it.seen.add("kts") }
        assertEquals(listOf("kts"), step.seen)
    }

    private fun messages(e: Throwable): String =
        generateSequence(e) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")

    /** 1.x `mixinRemap { }` surface calls must surface the
     *  `modArtifacts { }` migration note through Groovy dispatch —
     *  not a bare missing-method error. */
    @Test
    fun `removed mixinRemap verb warns with migration note`() {
        val project = ProjectBuilder.builder().build()
        val spec = extension(project).surface(RemapSurface.JAR)
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("spec.mixinRemap { }")
        }
        val msg = messages(e)
        assertTrue(msg.contains("modArtifacts"), msg)
    }

    /** 1.x `devNamespace`/`prodNamespace` surface setters — removed
     *  in 2.0; namespaces come from the environment declaration. */
    @Test
    fun `removed namespace verbs warn with migration note`() {
        val project = ProjectBuilder.builder().build()
        val spec = extension(project).surface(RemapSurface.JAR)
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("spec.devNamespace('official')")
        }
        assertTrue(messages(e).contains("namespace"), messages(e))
    }

    /** Engine verbs whose contributing plugin is absent name the
     *  plugin — `jst { }` without `remapper.jst` applied. */
    @Test
    fun `plugin-contributed verb names its plugin`() {
        val project = ProjectBuilder.builder().build()
        val spec = extension(project).surface(RemapSurface.JAR)
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("spec.jst { }")
        }
        assertTrue(messages(e).contains("remapper.jst"), messages(e))
    }

    /** Truly unknown surface calls still raise missing-method —
     *  the tombstone maps never swallow typos. */
    @Test
    fun `unknown surface verbs raise missing-method`() {
        val project = ProjectBuilder.builder().build()
        val spec = extension(project).surface(RemapSurface.JAR)
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        assertThrows(Exception::class.java) {
            shell.evaluate("spec.definitelyNotAVerb(1)")
        }
        assertThrows(MissingMethodException::class.java) {
            spec.methodMissing("definitelyNotAVerb", arrayOf<Any>(1))
        }
    }

}
