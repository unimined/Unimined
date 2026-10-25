package xyz.wagyourtail.unimined

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end vertical slice: a real Fabric mod project applying the
 * `xyz.wagyourtail.unimined` frontend — the native provider downloads MC
 * 1.20.1, fabric's intermediary hop lands the dev artifact on
 * `intermediary`, and the mod compiles against intermediary-named MC
 * classes plus the loader jar. Requires network access to Mojang and
 * maven.fabricmc.net on first run.
 */
class FabricModFunctionalTest {

    @TempDir
    lateinit var dir: Path

    private fun writeProject() {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'testmod'\n")
        // remapping a full MC jar inside the daemon needs real heap —
        // the default 512m OOMs mid-transform
        Files.writeString(dir.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx4g\n")
        Files.writeString(
            dir.resolve("build.gradle"),
            """
            plugins {
                id 'java'
                id 'xyz.wagyourtail.unimined'
            }

            repositories {
                mavenCentral()
            }

            unimined {
                minecraft {
                    version = '1.20.1'
                    loader 'fabric', '0.16.0'
                }
            }
            """.trimIndent()
        )
        val javaDir = dir.resolve("src/main/java/com/example")
        Files.createDirectories(javaDir)
        // class_2960 is Identifier's intermediary name — compiling against
        // it proves the dev artifact is genuinely intermediary-remapped
        Files.writeString(
            javaDir.resolve("TestMod.java"),
            """
            package com.example;

            import net.fabricmc.api.ModInitializer;
            import net.minecraft.class_2960;

            public class TestMod implements ModInitializer {
                @Override
                public void onInitialize() {
                    Object id = class_2960.method_43902("testmod", "entry");
                }
            }
            """.trimIndent()
        )
        val resourcesDir = dir.resolve("src/main/resources")
        Files.createDirectories(resourcesDir)
        Files.writeString(
            resourcesDir.resolve("fabric.mod.json"),
            """
            {
              "schemaVersion": 1,
              "id": "testmod",
              "version": "1.0.0",
              "name": "Test Mod",
              "environment": "*",
              "entrypoints": { "main": ["com.example.TestMod"] },
              "depends": { "fabricloader": ">=0.16.0", "minecraft": "~1.20.1" }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `fabric mod compiles against intermediary-dev minecraft`() {
        writeProject()
        val result = GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments("compileJava", "-s")
            .build()
        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
        assertTrue(
            Files.exists(
                dir.resolve("build/classes/java/main/com/example/TestMod.class")
            )
        )
    }

}
