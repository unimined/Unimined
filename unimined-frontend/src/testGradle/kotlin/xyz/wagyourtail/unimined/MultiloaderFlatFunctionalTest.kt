package xyz.wagyourtail.unimined

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Flat multiloader envs exercise implicit parent-container inheritance —
 * `sourceSet("x")` envs combine `main` via a post-evaluation hook that
 * must not trip Gradle 9.8's mutation guard during plugin application.
 */
class MultiloaderFlatFunctionalTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `flat multiloader envs configure without mutation guard failure`() {
        Files.writeString(dir.resolve("settings.gradle"),
            "rootProject.name = 'multiloader-flat'\n")
        Files.writeString(dir.resolve("build.gradle"), """
            plugins {
                id 'xyz.wagyourtail.unimined'
            }

            unimined {
                minecraft {
                    version.set('1.20.1')
                    sourceSet('fabric') {
                        loader('0.20.1')
                    }
                    sourceSet('neoforge') {
                        loader('26.1.2.126')
                    }
                }
            }

            tasks.register('dslCheck')
            """.trimIndent()
        )
        val result = GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments("dslCheck", "--stacktrace")
            .build()
        assertNotNull(result.task(":dslCheck"))
    }
}
