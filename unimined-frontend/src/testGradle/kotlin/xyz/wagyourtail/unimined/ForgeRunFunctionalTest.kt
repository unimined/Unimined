package xyz.wagyourtail.unimined

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Forge-era run-wiring verification via `-Punimined.run.dryRun` — the
 * runs task resolves the real forge artifact, probes the profile
 * (FG2 dev-package vs FG3+ config.json), applies the run mutators,
 * and prints the assembled launch instead of exec'ing MC. Requires
 * network to maven.minecraftforge.net / maven.neoforged.net /
 * Mojang on first run.
 */
class ForgeRunFunctionalTest {

    @TempDir
    lateinit var dir: Path

    private fun writeProject(
        mc: String, loader: String,
        loaderVersion: String,
        mappings: String
    ) {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'forgemod'\n")
        Files.writeString(
            dir.resolve("gradle.properties"),
            "org.gradle.jvmargs=-Xmx4g\n"
        )
        Files.writeString(
            dir.resolve("build.gradle"),
            """
            plugins {
                id 'java'
                id 'xyz.wagyourtail.unimined'
            }

            repositories {
                mavenCentral()
                maven { url 'https://maven.minecraftforge.net/' }
                maven { url 'https://maven.neoforged.net/releases/' }
            }

            unimined {
                minecraft {
                    version = '$mc'
                    loader '$loader', '$loaderVersion'
                    mappings {
                        $mappings
                    }
                }
            }
            """.trimIndent()
        )
        val javaDir = dir.resolve("src/main/java/com/example")
        Files.createDirectories(javaDir)
        Files.writeString(
            javaDir.resolve("Init.java"),
            "package com.example;\npublic class Init {}\n"
        )
    }

    private fun dryRun(task: String): String {
        val result = GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments(task, "-Punimined.run.dryRun=true", "-s")
            .build()
        return result.output
    }

    private fun String.line(prefix: String) =
        lineSequence().firstOrNull { it.contains("$prefix=") }
            ?.substringAfter("$prefix=")

    /**
     * FG2 (1.12.2, dev package with `dev.json`): launchwrapper main,
     * tweak class from the jar, `MOD_CLASSES` in `group%%dir` shape.
     */
    @Test
    fun `fg2 client run resolves launchwrapper + MOD_CLASSES`() {
        writeProject("1.12.2", "forge", "14.23.5.2847", "searge()")
        val out = dryRun("runClient")
        assertTrue(
            out.line("mainClass") == "net.minecraft.launchwrapper.Launch",
            "expected Launch main, got: ${out.line("mainClass")}\n$out"
        )
        assertTrue(
            out.line("programArgs")?.contains("--tweakClass") == true,
            "expected --tweakClass in args\n$out"
        )
        val modClasses = out.line("env.MOD_CLASSES") ?: ""
        assertTrue(
            modClasses.contains("%%"),
            "MOD_CLASSES must carry group%%dir pairs, got: $modClasses"
        )
    }

    /**
     * FG3-era forge 1.20.1 (forge <49 — no bootstrap-dev): userdev
     * `main` + `MOD_CLASSES` + unprotect javaagent.
     */
    @Test
    fun `fg3 client run applies userdev launch + unprotect`() {
        writeProject("1.20.1", "forge", "47.3.0", "mojmap()")
        val out = dryRun("runClient")
        val main = out.line("mainClass")
        assertTrue(
            main != null && main.isNotBlank() &&
                    main != "net.minecraft.launchwrapper.Launch",
            "expected the userdev-declared main, got: $main\n$out"
        )
        assertTrue(
            out.line("jvmArgs")?.contains("-javaagent:") == true &&
                    out.line("jvmArgs")?.contains("unprotect.mappings") == true,
            "expected unprotect agent + mappings jvmArgs\n$out"
        )
        val modClasses = out.line("env.MOD_CLASSES") ?: ""
        assertTrue(
            modClasses.contains("%%"),
            "MOD_CLASSES must carry group%%dir pairs, got: $modClasses"
        )
    }

    /**
     * FG3-era forge >=49 (mc 1.20.4): bootstrap-dev must land on the
     * classpath; `main` is forge's own BootstrapDevMain — never wrapped.
     */
    @Test
    fun `forge 49+ run carries bootstrap-dev without union relauncher`() {
        writeProject("1.20.4", "forge", "49.0.50", "mojmap()")
        val out = dryRun("runClient")
        assertTrue(
            !out.contains("unionrelauncher"),
            "union-relauncher must not appear in a forge 49+ run\n$out"
        )
        assertTrue(
            out.contains("bootstrap-dev"),
            "expected net.minecraftforge:bootstrap-dev on the classpath\n$out"
        )
    }

    /**
     * FG3-era with yarn as the dev namespace — the launch assembly
     * (MOD_CLASSES, unprotect, tweak args) must not depend on the
     * mapping choice.
     */
    @Test
    fun `fg3 client run is mapping-agnostic (yarn)`() {
        writeProject("1.20.1", "forge", "47.3.0", "yarn(8)")
        val out = dryRun("runClient")
        val modClasses = out.line("env.MOD_CLASSES") ?: ""
        assertTrue(
            modClasses.contains("%%"),
            "MOD_CLASSES must carry group%%dir pairs, got: $modClasses\n$out"
        )
    }
}
