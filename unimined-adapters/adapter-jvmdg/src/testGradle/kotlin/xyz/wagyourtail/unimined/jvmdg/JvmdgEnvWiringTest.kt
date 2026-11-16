package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.jvmdg.gradle.flags.ShadeFlags
import java.nio.file.Path

/**
 * Verifies the jvmdg environment wiring: opt-in `enabled` semantics,
 * the `source`/`target` pair feeding [MinecraftEnvironment.compileJavaVersion],
 * and the deferred prod-jar downgrade contribution.
 *
 * `JvmdgFakeProvider` (SPI-registered on this test classpath) reports
 * `javaVersion = 8` capabilities for any declared version.
 */
class JvmdgEnvWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.runs")
            project.plugins.apply("xyz.wagyourtail.unimined.jvmdg")
        }

    private fun env(project: Project, version: String = "1.12.2"): MinecraftEnvironment {
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.provider.set("fake")
        return ext.main.apply {
            this.version.set(version)
        }
    }

    private fun spec(environment: MinecraftEnvironment): JvmDgEnvSpec =
        environment.extensions.getByType(JvmDgEnvSpec::class.java)

    @Test
    fun `applying the plugin leaves every env disabled until configured`() {
        val project = project()
        assertFalse(spec(env(project)).enabled.get())
    }

    @Test
    fun `jvmdowngrader block opts the env in`() {
        val environment = env(project())
        environment.jvmdowngrader(Action {})
        assertTrue(spec(environment).enabled.get())
    }

    @Test
    fun `explicit enabled false inside the block still wins`() {
        val environment = env(project())
        environment.jvmdowngrader(Action { it.enabled.set(false) })
        assertFalse(spec(environment).enabled.get())
    }

    @Test
    fun `jvmdowngrader(int) enables and sets target`() {
        val environment = env(project())
        environment.jvmdowngrader(8)
        spec(environment).let {
            assertTrue(it.enabled.get())
            assertEquals(8, it.target.get())
        }
    }

    @Test
    fun `target conventions to the capabilities java floor`() {
        assertEquals(8, spec(env(project(), "1.12.2")).target.get())
    }

    @Test
    fun `target floors at 7 for pre-1_6_4-era versions`() {
        assertEquals(7, spec(env(project(), "1.5.2")).target.get())
    }

    @Test
    fun `source raises compileJavaVersion without moving the run floor`() {
        val environment = env(project())
        environment.jvmdowngrader(Action { it.source.set(21) })
        assertEquals(21, environment.compileJavaVersion.get())
        // run launchers and tool-exec still see the era requirement
        assertEquals(8, environment.requiredJavaVersion.get())
    }

    @Test
    fun `unset source keeps compileJavaVersion at the requirement`() {
        val environment = env(project())
        environment.jvmdowngrader(8)
        assertEquals(8, environment.compileJavaVersion.get())
    }

    @Test
    fun `prod jar contribution is empty while disabled`() {
        val project = project()
        env(project)
        val steps = project.extensions.getByType(RemapExtension::class.java)
            .sourceSet("main").contributedSteps(RemapSurface.JAR)
        assertTrue(steps.none { it is JvmDowngradeStepSpec })
    }

    @Test
    fun `prod jar contribution is empty when compile stays at target`() {
        val project = project()
        val environment = env(project)
        environment.jvmdowngrader(8) // enabled, but compile == target
        val steps = project.extensions.getByType(RemapExtension::class.java)
            .sourceSet("main").contributedSteps(RemapSurface.JAR)
        assertTrue(steps.none { it is JvmDowngradeStepSpec })
    }

    @Test
    fun `prod jar contribution lands when source raises the compile level`() {
        val project = project()
        val environment = env(project)
        environment.jvmdowngrader(Action {
            it.source.set(21)
            it.target.set(8)
        })
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val steps = remap.sourceSet("main")
            .contributedSteps(RemapSurface.JAR)
        val downgrades = steps.filterIsInstance<JvmDowngradeStepSpec>()
        assertEquals(1, downgrades.size)
        // ModArtifactOnly keeps it off the MC artifact; downgradeTo
        // chains the env's target (8 -> JavaVersion 1.8 -> major 52)
        assertTrue(downgrades.single() is ModArtifactOnlyStepSpec)
        assertEquals(JavaVersion.VERSION_1_8,
            (downgrades.single() as ShadeFlags).downgradeTo.get())
        // ENVIRONMENT_ARTIFACT scope keeps it out of remapped
        // dependency views
        assertTrue(remap.sourceSet("main")
            .contributedSteps(RemapSurface.JAR, StepScope.ALL_ARTIFACTS)
            .none { it is JvmDowngradeStepSpec })
    }

    @Test
    fun `an explicit target overrides the era floor`() {
        val spec = spec(env(project(), "1.12.2"))
        spec.target.set(11)
        // downgradeTo chains target — the override flows to the engine
        assertEquals(JavaVersion.VERSION_11, spec.downgradeTo.get())
    }

    @Test
    fun `a direct downgradeTo wins over target`() {
        val spec = spec(env(project(), "1.12.2"))
        spec.target.set(8)
        spec.downgradeTo.set(JavaVersion.VERSION_11)
        assertEquals(JavaVersion.VERSION_11, spec.downgradeTo.get())
    }

    @Test
    fun `an overridden target reaches the prod contribution`() {
        val project = project()
        val environment = env(project)
        environment.jvmdowngrader(Action {
            it.source.set(21)
            it.target.set(11) // emit 55, not the era floor's 52
        })
        val steps = project.extensions.getByType(RemapExtension::class.java)
            .sourceSet("main").contributedSteps(RemapSurface.JAR)
        val downgrades = steps.filterIsInstance<JvmDowngradeStepSpec>()
        assertEquals(1, downgrades.size)
        assertEquals(JavaVersion.VERSION_11,
            (downgrades.single() as ShadeFlags).downgradeTo.get())
    }

    @Test
    fun `the extension-level spec is global — every env opts in`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.provider.set("fake")
        val main = ext.main.apply { version.set("1.12.2") }
        val client = ext.sourceSet("client").apply { version.set("1.12.2") }
        ext.jvmdowngrader(Action { it.source.set(21) })
        assertTrue(spec(main).enabled.get())
        assertTrue(spec(client).enabled.get())
        assertEquals(21, main.compileJavaVersion.get())
        assertEquals(21, client.compileJavaVersion.get())
    }

    @Test
    fun `env-level settings beat the global spec`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val environment = env(project)
        ext.jvmdowngrader(Action {
            it.source.set(21)
            it.target.set(11)
        })
        environment.jvmdowngrader(Action {
            it.enabled.set(false)
            it.target.set(8)
        })
        spec(environment).let {
            assertFalse(it.enabled.get())
            assertEquals(8, it.target.get())
        }
    }

    @Test
    fun `global engine flags reach env specs`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val environment = env(project)
        ext.jvmdowngrader(Action {
            it.logAnsiColors.set(false)
            it.ignoreWarningsIn.add("com.example.**")
        })
        spec(environment).let {
            assertFalse(it.logAnsiColors.get())
            assertEquals(listOf("com.example.**"), it.ignoreWarningsIn.get())
        }
    }

    @Test
    fun `a raised javaVersion alone does not trigger the prod contribution`() {
        // javaVersion raises requiredJavaVersion — compileJavaVersion
        // follows — but the env only downgrades when the adapter's own
        // enabled flag is on
        val project = project()
        val environment = env(project)
        environment.javaVersion.set(21)
        val steps = project.extensions.getByType(RemapExtension::class.java)
            .sourceSet("main").contributedSteps(RemapSurface.JAR)
        assertTrue(steps.none { it is JvmDowngradeStepSpec })
        assertEquals(21, environment.compileJavaVersion.get())
    }

}
