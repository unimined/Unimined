package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** `mods { "<id>" { } }` group spec — error messages name the group. */
class ModGroupSpecTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `missing source set error names the group id`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile())
            .build()
        project.plugins.apply("java")
        val group = project.objects.newInstance(
            ModGroupSpec::class.java, "mymod", project
        )
        val e = assertThrows<GradleException> {
            group.sourceSet("doesNotExist")
        }
        assertTrue(e.message!!.contains("mymod"), e.message!!)
        assertTrue(e.message!!.contains("doesNotExist"), e.message!!)
    }

}
