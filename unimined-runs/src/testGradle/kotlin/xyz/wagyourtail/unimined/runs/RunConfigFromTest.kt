package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `RunConfig.from` — lazy convention copy of the source's spec. */
class RunConfigFromTest {

    private fun rc(project: org.gradle.api.Project, name: String) =
        project.objects.newInstance(RunConfig::class.java, name)

    @Test
    fun `from copies unset properties and keeps explicit overrides`() {
        val project = ProjectBuilder.builder().build()
        val src = rc(project, "client").apply {
            username.set("SrcUser")
            jvmArgs.set(listOf("-Xmx2G"))
            prod.set(true)
        }
        val dst = rc(project, "dst").apply {
            jvmArgs.set(listOf("-Xmx4G")) // explicit beats convention
            from(src)
        }
        assertEquals("SrcUser", dst.username.get())
        assertEquals(listOf("-Xmx4G"), dst.jvmArgs.get())
        assertEquals(true, dst.prod.get())
    }

    @Test
    fun `from(provider) tracks later source mutation`() {
        val project = ProjectBuilder.builder().build()
        val src = rc(project, "src")
        val dst = rc(project, "dst")
        dst.from(project.provider { src })
        src.username.set("Late")
        assertEquals("Late", dst.username.get())
    }

}
