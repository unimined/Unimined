package xyz.wagyourtail.unimined

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.runs.AuthSpec
import xyz.wagyourtail.unimined.runs.RunConfig
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.minecraft.GradlePlugin as MinecraftPlugin
import xyz.wagyourtail.unimined.remap.GradlePlugin as RemapPlugin
import xyz.wagyourtail.unimined.transform.GradlePlugin as TransformPlugin
import xyz.wagyourtail.unimined.runs.GradlePlugin as RunsPlugin

class UniminedExtensionGroovyTest {

    @Test
    fun `Groovy closure overloads delegate to the matching extensions`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(MinecraftPlugin::class.java)
        project.plugins.apply(RemapPlugin::class.java)
        project.plugins.apply(TransformPlugin::class.java)
        val facade = project.extensions.create(
            "unimined",
            UniminedExtension::class.java,
            project
        )
        val delegates = mutableListOf<Any?>()

        facade.minecraft(closure(delegates) { delegate ->
            val minecraft = delegate as MinecraftExtension
            minecraft.version.set("1.20.1")
        })
        facade.remap(closure(delegates) { delegate ->
            (delegate as RemapExtension).jar { it.remap() }
        })
        facade.transform(closure(delegates) { delegate ->
            assertTrue((delegate as TransformExtension).transformers.isEmpty())
        })

        assertEquals(3, delegates.size)
        assertTrue(delegates[0] is MinecraftExtension)
        assertTrue(delegates[1] is RemapExtension)
        assertTrue(delegates[2] is TransformExtension)
        assertEquals("1.20.1", project.extensions.getByType(MinecraftExtension::class.java).version.get())
    }

    @Test
    fun `Groovy closure twins delegate on runs and mappings verbs`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(RunsPlugin::class.java)
        val runs = project.extensions.getByType(RunsExtension::class.java)

        val delegates = mutableListOf<Any?>()
        runs.auth(closure(delegates) { (it as AuthSpec).offline("Dev0") })
        runs.named("client", closure(delegates) { (it as RunConfig).mainClass.set("net.x.Client") })
        runs.create("ci", closure(delegates) { (it as RunConfig).mainClass.set("net.x.Ci") })

        assertTrue(delegates[0] is AuthSpec)
        assertTrue(delegates[1] is RunConfig)
        assertTrue(delegates[2] is RunConfig)
        assertEquals("Dev0", runs.auth.username.get())
        assertEquals("net.x.Client", runs.runs.getByName("client").mainClass.get())

        // per-run auth twin
        delegates.clear()
        runs.runs.getByName("client").auth(closure(delegates) { (it as AuthSpec).offline("Run") })
        assertTrue(delegates[0] is AuthSpec)
        assertEquals("Run", runs.runs.getByName("client").auth.username.get())

        // mapping-source blocks — the Closure twin is what makes
        // `file("x") { mapNamespace(...) }` work in Groovy (fn-type
        // params don't SAM-coerce)
        val spec = MappingsSpecImpl(project.objects)
        delegates.clear()
        val captured = mutableListOf<MappingsSpecImpl.Source>()
        spec.file("mappings.tiny", closure(delegates) {
            captured += it as MappingsSpecImpl.Source; captured.last().mapNamespace("a", "b")
        })
        spec.maven("g:a:v", closure(delegates) {
            captured += it as MappingsSpecImpl.Source; captured.last().formatHint("tiny")
        })
        spec.builtin("official", emptyMap(), closure(delegates) {
            captured += it as MappingsSpecImpl.Source; captured.last().formatHint("proguard")
        })
        assertEquals(3, captured.size)
        assertEquals(mapOf("a" to "b"), captured[0].nsMap)
        assertEquals("tiny", captured[1].formatHint)
        assertEquals("proguard", captured[2].formatHint)
    }

    private fun closure(
        delegates: MutableList<Any?>,
        action: (Any?) -> Unit
    ): Closure<Any?> = object : Closure<Any?>(this) {
        fun doCall() {
            val target = getDelegate()
            delegates += target
            action(target)
        }
    }

}
