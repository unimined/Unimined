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
            launchProperties.put("k", "v")
            prod.set(true)
        }
        val dst = rc(project, "dst").apply {
            jvmArgs.set(listOf("-Xmx4G")) // explicit beats convention
            from(src)
        }
        assertEquals("SrcUser", dst.username.get())
        assertEquals(listOf("-Xmx4G"), dst.jvmArgs.get())
        assertEquals(mapOf("k" to "v"), dst.launchProperties.get())
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

    @Test
    fun `from carries unitTestSources lazily`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        val sourceSets = project.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        val test = sourceSets.getByName("test")
        val it2 = sourceSets.create("integrationTest")
        val src = rc(project, "src").apply { unitTestSources.add(test) }
        val dst = rc(project, "dst").apply { from(src) }
        // added after `from` — the copy is a live view, not a snapshot
        src.unitTestSources.add(it2)
        assertEquals(setOf(test, it2), dst.unitTestSources)
    }

    @Test
    fun `from(provider) carries unitTestSources lazily`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        val sourceSets = project.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        val test = sourceSets.getByName("test")
        val it2 = sourceSets.create("integrationTest")
        val src = rc(project, "src").apply { unitTestSources.add(test) }
        val dst = rc(project, "dst")
        dst.from(project.provider { src })
        src.unitTestSources.add(it2)
        assertEquals(setOf(test, it2), dst.unitTestSources)
    }

}
