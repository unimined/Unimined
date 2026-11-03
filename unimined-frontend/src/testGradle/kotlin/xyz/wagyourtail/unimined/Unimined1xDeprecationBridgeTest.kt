package xyz.wagyourtail.unimined

import groovy.lang.GroovyShell
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.GradlePlugin as MinecraftPlugin
import xyz.wagyourtail.unimined.remap.GradlePlugin as RemapPlugin
import xyz.wagyourtail.unimined.transform.GradlePlugin as TransformPlugin

/**
 * 1.x `unimined { }` verbs that moved or were removed in 2.0 must
 * surface migration notes (not bare missing-method errors) when hit
 * from a Groovy script — see `UniminedExtension.methodMissing`.
 */
class Unimined1xDeprecationBridgeTest {

    private fun facade(): UniminedExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(MinecraftPlugin::class.java)
        project.plugins.apply(RemapPlugin::class.java)
        project.plugins.apply(TransformPlugin::class.java)
        return project.extensions.create(
            "unimined", UniminedExtension::class.java, project)
    }

    private fun eval(unimined: UniminedExtension, code: String) {
        GroovyShell().also { it.setVariable("unimined", unimined) }
            .evaluate(code)
    }

    @Test
    fun `removed maven repo helper warns with migration note`() {
        val e = assertThrows(Exception::class.java) {
            eval(facade(), "unimined.fabricMaven()")
        }
        val msg = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")
        assertTrue(msg.contains("fabricMaven") &&
            msg.contains("auto-registered"), msg)
    }

    @Test
    fun `old-shape minecraft block gets migration note`() {
        val e = assertThrows(Exception::class.java) {
            eval(facade(), "unimined.minecraft('main') { }")
        }
        val msg = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")
        assertTrue(msg.contains("moved in Unimined 2.0"), msg)
    }

    @Test
    fun `unknown verbs still raise missing-method`() {
        assertThrows(Exception::class.java) {
            eval(facade(), "unimined.notARealThing()")
        }
    }

}
