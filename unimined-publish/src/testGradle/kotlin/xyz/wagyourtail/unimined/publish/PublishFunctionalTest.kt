package xyz.wagyourtail.unimined.publish

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end publish slice: a real unimined project publishes to a
 * plain folder Maven repository (`publishing.repositories.maven`),
 * then the test inspects the generated POMs, Gradle module metadata,
 * and jar layout — the plan §11 acceptance check that unit tests
 * can't reach (variant realization, task execution, repository
 * layout).
 *
 * `withPluginClasspath()` only injects this module's *main* runtime
 * classpath, which lacks the frontend stack — so an init script puts
 * the whole testGradle classpath (frontend + stack + publish) onto
 * every project's buildscript classpath instead.
 *
 * Requires network access to Mojang/maven.fabricmc.net on first run
 * (same as the frontend functional tests).
 */
class PublishFunctionalTest {

    @TempDir
    lateinit var dir: Path

    private fun repo() = dir.resolve("repo")

    private fun writeProject() {
        Files.writeString(
            dir.resolve("settings.gradle"),
            "rootProject.name = 'testmod'\n"
        )
        // remapping a full MC jar inside the daemon needs real heap
        Files.writeString(
            dir.resolve("gradle.properties"),
            "org.gradle.jvmargs=-Xmx4g\n"
        )
        Files.writeString(
            dir.resolve("build.gradle"),
            """
            plugins { id 'java' }

            apply plugin: 'xyz.wagyourtail.unimined'
            apply plugin: 'xyz.wagyourtail.unimined.publish'

            group = 'com.example'
            version = '1.0.0'

            repositories {
                mavenCentral()
                maven { url = 'https://maven.fabricmc.net/' }
            }

            unimined {
                minecraft {
                    version = '1.20.1'
                    sourceSet('fabric') {
                        loader '0.16.0'
                    }
                }
            }

            publishing {
                repositories {
                    maven { url = uri('${repo()}') }
                }
            }
            """.trimIndent()
        )
        val javaDir = dir.resolve("src/main/java/com/example")
        Files.createDirectories(javaDir)
        Files.writeString(
            javaDir.resolve("TestMod.java"),
            """
            package com.example;

            public class TestMod {
                public static final String MOD_ID = "testmod";
            }
            """.trimIndent()
        )
    }

    /** Init script putting the test classpath on every project's
     *  buildscript classpath — the TestKit build resolves the plugin
     *  ids from the marker properties inside those jars/dirs. */
    private fun writeInitScript(): Path {
        val entries = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .joinToString(", ") { "'${it.replace("'", "\\'")}'" }
        val init = dir.resolve("init-classpath.gradle")
        Files.writeString(
            init,
            """
            allprojects { p ->
                p.buildscript {
                    dependencies {
                        classpath p.files($entries)
                    }
                }
            }
            """.trimIndent()
        )
        return init
    }

    private fun publish() = GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withArguments("publish", "-s", "-I", writeInitScript().toString())
        .build()

    @Test
    fun `publish writes per-environment maven modules to a folder repo`() {
        writeProject()
        val result = publish()
        assertTrue(
            result.output.contains("BUILD SUCCESSFUL"),
            "publish failed:\n${result.output}"
        )

        // one Maven module per environment — main keeps the project
        // name, fabric gets the -fabric suffix (plan §11)
        for ((module, env) in listOf("testmod" to "main", "testmod-fabric" to "fabric")) {
            val base = repo().resolve("com/example/$module/1.0.0")
            assertTrue(Files.exists(base.resolve("$module-1.0.0.jar")),
                "missing jar for $module:\n${result.output}")
            assertTrue(Files.exists(base.resolve("$module-1.0.0.pom")),
                "missing pom for $module")
            assertTrue(Files.exists(base.resolve("$module-1.0.0.module")),
                "missing gradle module metadata for $module")
        }

        // POM coordinates come from the environment publication
        val fabricPom = Files.readString(
            repo().resolve("com/example/testmod-fabric/1.0.0/testmod-fabric-1.0.0.pom"))
        assertTrue(fabricPom.contains("<artifactId>testmod-fabric</artifactId>"),
            "fabric pom artifactId:\n$fabricPom")
        assertTrue(fabricPom.contains("<groupId>com.example</groupId>"))

        // variant attributes reach the published module metadata —
        // this is what lets consumers select dev/prod/sources variants.
        // GMM is pretty-printed, so match keys/values separately.
        val fabricModule = Files.readString(
            repo().resolve("com/example/testmod-fabric/1.0.0/testmod-fabric-1.0.0.module"))
        for (needle in listOf(
            "\"unimined.environment\"", "\"fabric\"",
            "\"unimined.channel\"", "\"prod\"", "\"dev\"",
            "\"unimined.artifactRole\"", "\"unimined.loader\""
        )) {
            assertTrue(fabricModule.contains(needle),
                "module metadata missing $needle:\n$fabricModule")
        }

        // dev-channel artifacts publish under classifiers alongside
        // the prod (null-classifier) main jar
        val fabricDir = repo().resolve("com/example/testmod-fabric/1.0.0")
        for (artifact in listOf(
            "testmod-fabric-1.0.0-dev.jar",
            "testmod-fabric-1.0.0-dev-sources.jar",
            "testmod-fabric-1.0.0-sources.jar"
        )) {
            assertTrue(Files.exists(fabricDir.resolve(artifact)),
                "missing $artifact in $fabricDir — contents: " +
                        fabricDir.toFile().list()?.sorted()?.joinToString(", "))
        }
    }

}
