package xyz.wagyourtail.unimined.mod

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Small manual-input smoke test proving the wiring is configuration-cache clean. */
class EraMergeFunctionalTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `manual chimera input merges and configuration cache is reused`() {
        Files.createDirectories(dir.resolve("input/com/example"))
        Files.writeString(dir.resolve("input/com/example/Manual.class"), "manual")
        Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"era-merge-test\"\n")
        Files.writeString(
            dir.resolve("build.gradle.kts"),
            """
            import org.gradle.jvm.tasks.Jar

            plugins {
                java
                id("xyz.wagyourtail.unimined.mod")
            }

            val manualInput by tasks.registering(Jar::class) {
                archiveBaseName.set("manual")
                archiveClassifier.set("input")
                destinationDirectory.set(layout.buildDirectory.dir("inputs"))
                from("input")
            }

            uniminedMod {
                chimeraJar {
                    eras("manual")
                    era("manual") {
                        jar.set(manualInput.flatMap { it.archiveFile })
                        loader.set("forge")
                    }
                    overlapValidation.set("off")
                    manifest {
                        attribute("MixinConfigs", "manual.mixins.json")
                    }
                }
            }
            """.trimIndent()
        )

        fun run() = GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments("chimeraJar", "--configuration-cache", "--stacktrace")
            .build()

        val first = run()
        assertNotNull(first.task(":chimeraJar"))
        val second = run()
        assertNotNull(second.task(":chimeraJar"))
        assertTrue(
            second.output.contains("Reusing configuration cache") ||
                second.output.contains("Configuration cache entry reused"),
            second.output
        )
        assertTrue(Files.exists(dir.resolve("build/libs/era-merge-test-unspecified-chimera.jar")))
    }
}
