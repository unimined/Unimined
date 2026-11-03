package xyz.wagyourtail.unimined

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DslParityFunctionalTest {

    @TempDir
    lateinit var dir: Path

    private fun runDsl(scriptName: String, script: String) {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'dsl-parity-test'\n")
        Files.writeString(dir.resolve(scriptName), script)
        val result = GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments("dslCheck", "--stacktrace")
            .build()
        assertNotNull(result.task(":dslCheck"))
    }

    @Test
    fun `groovy DSL configures frontend blocks`() {
        runDsl(
            "build.gradle",
            """
            plugins {
                id 'xyz.wagyourtail.unimined'
            }

            unimined {
                minecraft {
                    version.set('1.20.1')
                }
                remap {
                    jar {
                        remap()
                    }
                }
            }

            tasks.register('dslCheck')
            """.trimIndent()
        )
    }

    @Test
    fun `kotlin DSL configures frontend blocks`() {
        runDsl(
            "build.gradle.kts",
            """
            plugins {
                id("xyz.wagyourtail.unimined")
            }

            unimined {
                minecraft {
                    version.set("1.20.1")
                }
                remap {
                    jar {
                        remap()
                    }
                }
            }

            tasks.register("dslCheck")
            """.trimIndent()
        )
    }

}
