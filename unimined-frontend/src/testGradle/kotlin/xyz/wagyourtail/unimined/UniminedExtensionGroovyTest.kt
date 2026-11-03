package xyz.wagyourtail.unimined

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.minecraft.GradlePlugin as MinecraftPlugin
import xyz.wagyourtail.unimined.remap.GradlePlugin as RemapPlugin
import xyz.wagyourtail.unimined.transform.GradlePlugin as TransformPlugin

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
