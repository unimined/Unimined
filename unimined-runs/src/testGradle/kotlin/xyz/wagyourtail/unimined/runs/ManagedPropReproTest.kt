package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which creation paths produce working managed `abstract val`s. */
class ManagedPropReproTest {

    @Test
    fun `extensions-create`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create("uniminedRuns", RunsExtension::class.java)
        println("class = ${ext.javaClass.name}")
        ext.enabled.set(false)
        assertEquals(false, ext.enabled.get())
    }

    @Test
    fun `objects-newInstance`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.objects.newInstance(RunsExtension::class.java)
        println("class = ${ext.javaClass.name}")
        ext.enabled.set(false)
        assertEquals(false, ext.enabled.get())
    }

    @Test
    fun `container element`() {
        val project = ProjectBuilder.builder().build()
        val c = project.objects.domainObjectContainer(RunConfig::class.java)
        val rc = c.create("client")
        println("class = ${rc.javaClass.name}")
        rc.enabled.set(false)
        assertEquals(false, rc.enabled.get())
    }
}
