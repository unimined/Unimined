package xyz.wagyourtail.unimined.transform.jarmod

import groovy.lang.GroovyShell
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * `env.jarmod("v")` from a Groovy `build.gradle` — a non-closure call,
 * so dispatch resolves the `jarmod` named extension and invokes its
 * `jarmod` method. Requires the decorated (objects.newInstance) env
 * for extension storage dispatch to work.
 */
class JarModGroovyDispatchTest {

    @Test
    fun `env jarmod call dispatches from groovy evaluation`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("main")
        GroovyShell().also { it.setVariable("env", env) }
            .evaluate("env.jarmod('0.1.5')")
        assertEquals("jarmod", env.loaderSpec.get().name)
    }

}
