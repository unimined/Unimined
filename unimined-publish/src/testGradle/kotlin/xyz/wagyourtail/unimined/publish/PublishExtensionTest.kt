package xyz.wagyourtail.unimined.publish

import groovy.lang.GroovyShell
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Acceptance test per plan §11: the publish host creates per-env
 * components, keeps `components.java` opt-in, and never creates a
 * default Java publication.
 */
class PublishExtensionTest {

    @Test
    fun `javaPublication is opt-in not default`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        val ext = project.extensions.getByType(PublishExtension::class.java)
        assertFalse(ext.javaPublication.get(), "java publication must be opt-in")
        ext.javaPublication()
        assertTrue(ext.javaPublication.get())
    }

    @Test
    fun `environment settings accumulate`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        val ext = project.extensions.getByType(PublishExtension::class.java)
        ext.environment("main") {
            it.groupId = "com.example"
            it.artifactId = "my-mod"
            it.publishDev = false
        }
        val env = ext.environments["main"]
        assertNotNull(env)
        env!!
        assertTrue(env.publishProduction)
        assertFalse(env.publishDev)
    }

    @Test
    fun `environment groovy closure delegates to the publication spec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        val ext = project.extensions.getByType(PublishExtension::class.java)

        val shell = GroovyShell()
        shell.setVariable("ext", ext)
        shell.evaluate(
            "ext.environment('main') {\n" +
                    "    groupId = 'com.example'\n" +
                    "    version = '9.9'\n" +
                    "    publishDataFiles = false\n" +
                    "}"
        )

        val env = ext.environments["main"]!!
        assertEquals("com.example", env.groupId)
        assertEquals("9.9", env.version)
        assertFalse(env.publishDataFiles)
    }

}
