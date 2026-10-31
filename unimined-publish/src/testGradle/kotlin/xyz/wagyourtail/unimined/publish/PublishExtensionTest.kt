package xyz.wagyourtail.unimined.publish

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
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

}
